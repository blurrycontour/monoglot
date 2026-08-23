package httpapi

import (
	"encoding/csv"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/adityasingh/svenska/api/internal/lexicon"
)

// langOr reads ?lang=, falling back to the instance default.
func langOr(r *http.Request) string {
	if v := r.URL.Query().Get("lang"); v != "" {
		return v
	}
	return lexicon.DefaultLanguage
}

// lookup is the live fallback path. The client resolves from its offline
// bundle first and only reaches here on a miss.
func (s *Server) lookup(w http.ResponseWriter, r *http.Request) {
	surface := r.URL.Query().Get("w")
	if strings.TrimSpace(surface) == "" {
		badRequest(w, "missing ?w=")
		return
	}

	lang := r.URL.Query().Get("lang")
	if lang == "" {
		lang = lexicon.DefaultLanguage
	}
	res, err := lexicon.Lookup(r.Context(), s.pool, lang, surface)
	if err != nil {
		serverError(w, err)
		return
	}

	// Record the tap. Best effort: a logging failure must never break lookup.
	itemID := queryInt(r, "item_id", 0)
	tokenID := queryInt(r, "token_id", 0)
	go s.recordLookup(lang, itemID, tokenID, res)

	writeJSON(w, http.StatusOK, res)
}

func (s *Server) recordLookup(lang string, itemID, tokenID int, res lexicon.Result) {
	ctx, cancel := contextWithTimeout(5 * time.Second)
	defer cancel()

	lemma := res.Normalized
	if len(res.Candidates) > 0 {
		lemma = res.Candidates[0].Lemma
	}
	var itemArg, tokenArg any
	if itemID > 0 {
		itemArg = itemID
	}
	if tokenID > 0 {
		tokenArg = tokenID
	}
	if _, err := s.pool.Exec(ctx,
		`INSERT INTO lookups (item_id, token_id, lemma) VALUES ($1,$2,$3)`,
		itemArg, tokenArg, lemma); err != nil {
		return
	}
	// Touch the vocabulary row so the Words screen reflects real usage.
	s.pool.Exec(ctx, `
		INSERT INTO user_words (language_code, lemma, status, lookup_count)
		VALUES ($2, $1, 'unknown', 1)
		ON CONFLICT (language_code, lemma) DO UPDATE SET
		  lookup_count = user_words.lookup_count + 1,
		  last_seen = now()`, lemma, lang)
}

func (s *Server) postWordStatus(w http.ResponseWriter, r *http.Request) {
	lemma := strings.TrimSpace(chi.URLParam(r, "lemma"))
	if lemma == "" {
		badRequest(w, "missing lemma")
		return
	}
	var body struct {
		Status string `json:"status"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}
	switch body.Status {
	case "unknown", "learning", "known":
	default:
		badRequest(w, "status must be unknown, learning or known")
		return
	}

	lang := r.URL.Query().Get("lang")
	if lang == "" {
		lang = lexicon.DefaultLanguage
	}
	_, err := s.pool.Exec(r.Context(), `
		INSERT INTO user_words (language_code, lemma, status) VALUES ($3,$1,$2)
		ON CONFLICT (language_code, lemma) DO UPDATE SET
		  status=EXCLUDED.status, last_seen=now()`,
		lemma, body.Status, lang)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"lemma": lemma, "status": body.Status})
}

type WordRow struct {
	Lemma       string               `json:"lemma"`
	Status      string               `json:"status"`
	LookupCount int                  `json:"lookup_count"`
	FirstSeen   time.Time            `json:"first_seen"`
	LastSeen    time.Time            `json:"last_seen"`
	Definitions []lexicon.Definition `json:"definitions,omitempty"`
}

func (s *Server) listWords(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	rows, err := s.pool.Query(r.Context(), `
		SELECT uw.lemma, uw.status, uw.lookup_count, uw.first_seen, uw.last_seen,
		       COALESCE((SELECT l.definitions FROM lexemes l
		                 WHERE l.lemma = uw.lemma AND l.language_code = uw.language_code
		                 ORDER BY l.id LIMIT 1), '[]'::jsonb)
		FROM user_words uw
		WHERE ($1 = '' OR uw.status = $1) AND uw.language_code = $2
		ORDER BY uw.last_seen DESC`, status, langOr(r))
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := []WordRow{}
	for rows.Next() {
		var wr WordRow
		var raw []byte
		if err := rows.Scan(&wr.Lemma, &wr.Status, &wr.LookupCount,
			&wr.FirstSeen, &wr.LastSeen, &raw); err != nil {
			serverError(w, err)
			return
		}
		unmarshalDefs(raw, &wr.Definitions)
		out = append(out, wr)
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"words": out})
}

// exportAnki emits CSV rather than .apkg: Anki imports CSV natively, and this
// keeps a zip/sqlite writer out of the dependency tree for no real gain.
func (s *Server) exportAnki(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status == "" {
		// Default to what you are actually trying to learn.
		status = "learning"
	}
	rows, err := s.pool.Query(r.Context(), `
		SELECT uw.lemma, uw.status, uw.lookup_count,
		       COALESCE((SELECT l.definitions FROM lexemes l
		                 WHERE l.lemma = uw.lemma AND l.language_code = uw.language_code
		                 ORDER BY l.id LIMIT 1), '[]'::jsonb)
		FROM user_words uw
		WHERE ($1 = 'all' OR uw.status = $1) AND uw.language_code = $2
		ORDER BY uw.lookup_count DESC, uw.lemma`, status, langOr(r))
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename="svenska-%s-%s.csv"`,
			status, time.Now().Format("2006-01-02")))

	cw := csv.NewWriter(w)
	defer cw.Flush()
	cw.Write([]string{"Swedish", "English", "Example", "Lookups"})

	for rows.Next() {
		var lemma, st string
		var count int
		var raw []byte
		if err := rows.Scan(&lemma, &st, &count, &raw); err != nil {
			return
		}
		var defs []lexicon.Definition
		unmarshalDefs(raw, &defs)

		var trs, exs []string
		for _, d := range defs {
			trs = append(trs, d.Translation)
			if d.Example != "" && len(exs) == 0 {
				exs = append(exs, d.Example)
			}
		}
		cw.Write([]string{lemma, strings.Join(trs, "; "),
			strings.Join(exs, " "), fmt.Sprint(count)})
	}
}
