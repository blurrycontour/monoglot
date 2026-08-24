package lexicon

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Machine-translation fallback for words the dictionary does not have.
//
// Swedish compounds are productive: "smittspridningen" is a perfectly ordinary
// word that no fixed lexicon will ever list. Before this, a tap on one showed
// "no definition found", which is the least useful thing the app can say.
//
// This is deliberately a fallback and not a source of truth. Results are
// marked origin="translate" so the UI can say where they came from, they are
// never written into lexemes, and they never reach the ingestion bundle — a
// tap that hits this path is by definition already off the fast route.
const translateOrigin = "translate"

var translateClient = &http.Client{Timeout: 4 * time.Second}

// TranslateEnabled reports whether the fallback should be attempted.
// Off switches it off entirely; there is no partial mode.
func TranslateEnabled() bool {
	return os.Getenv("TRANSLATE_FALLBACK") != "0"
}

// TranslateFallback returns a single candidate for a word the dictionary
// missed, or false if nothing could be produced. Every failure is soft: a tap
// that finds no definition is a normal outcome, not an error worth surfacing.
func TranslateFallback(ctx context.Context, pool *sql.DB, lang, norm string) (Candidate, bool) {
	if norm == "" || !TranslateEnabled() {
		return Candidate{}, false
	}

	if text, provider, ok := cachedTranslation(ctx, pool, lang, norm); ok {
		return translateCandidate(norm, text, provider), true
	}

	text, provider, err := translate(ctx, lang, norm)
	if err != nil {
		log.Printf("translate %q: %v", norm, err)
		return Candidate{}, false
	}
	if text == "" {
		return Candidate{}, false
	}
	// A translation that just echoes the input tells the reader nothing and is
	// what the provider returns for a word it does not know either.
	if strings.EqualFold(strings.TrimSpace(text), norm) {
		return Candidate{}, false
	}

	storeTranslation(ctx, pool, lang, norm, text, provider)
	return translateCandidate(norm, text, provider), true
}

func translateCandidate(lemma, text, provider string) Candidate {
	return Candidate{
		Lemma:  lemma,
		Origin: translateOrigin,
		Definitions: []Definition{{
			Translation: text,
			Comment:     "machine translation (" + provider + ")",
		}},
	}
}

func cachedTranslation(ctx context.Context, pool *sql.DB, lang, norm string) (string, string, bool) {
	var text, provider string
	err := pool.QueryRowContext(ctx,
		`SELECT translation, provider FROM translations
		 WHERE language_code = ? AND surface = ?`, lang, norm).Scan(&text, &provider)
	if err != nil {
		return "", "", false
	}
	return text, provider, true
}

func storeTranslation(ctx context.Context, pool *sql.DB, lang, norm, text, provider string) {
	pool.ExecContext(ctx, `
		INSERT INTO translations (language_code, surface, translation, provider)
		VALUES (?,?,?,?)
		ON CONFLICT (language_code, surface) DO UPDATE SET
		  translation = excluded.translation,
		  provider = excluded.provider`, lang, norm, text, provider)
}

// translate calls MyMemory, a documented free translation-memory API.
//
// Chosen over Google's translate_a/single endpoint, which is undocumented and
// rate-limits non-browser clients: it returned 429 to this service while the
// identical request from curl succeeded. MyMemory allows anonymous use within
// a daily character quota, which a handful of taps a day never approaches.
// TRANSLATE_FALLBACK=0 turns the whole fallback off.
func translate(ctx context.Context, lang, word string) (string, string, error) {
	q := url.Values{}
	q.Set("q", word)
	q.Set("langpair", lang+"|en")

	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://api.mymemory.translated.net/get?"+q.Encode(), nil)
	if err != nil {
		return "", "", err
	}
	resp, err := translateClient.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", fmt.Errorf("translate: %s", resp.Status)
	}

	var body struct {
		ResponseData struct {
			TranslatedText string `json:"translatedText"`
		} `json:"responseData"`
		QuotaFinished bool `json:"quotaFinished"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", "", err
	}
	if body.QuotaFinished {
		return "", "", fmt.Errorf("translate: daily quota exhausted")
	}
	return cleanTranslation(body.ResponseData.TranslatedText), "mymemory", nil
}

// cleanTranslation strips translation-memory markup. Segments are contributed
// by users and carry XLIFF tags (<bpt>/<ept>), placeholder braces and trailing
// punctuation from whatever sentence they were lifted out of.
func cleanTranslation(s string) string {
	var b strings.Builder
	depth := 0
	for _, r := range s {
		switch {
		case r == '<':
			depth++
		case r == '>' && depth > 0:
			depth--
		case depth == 0:
			b.WriteRune(r)
		}
	}
	out := strings.ReplaceAll(b.String(), "{}", "")
	out = strings.Join(strings.Fields(out), " ")
	return strings.Trim(out, " .,;:")
}
