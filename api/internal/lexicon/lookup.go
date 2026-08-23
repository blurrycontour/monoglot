package lexicon

import (
	"context"
	"encoding/json"
	"strings"
	"unicode"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Candidate is one possible reading of a surface form. Swedish is genuinely
// ambiguous ("far" is both sheep and may/gets), so the API returns every
// candidate and the UI stacks them. No context disambiguation in v1.
type Candidate struct {
	Lemma       string       `json:"lemma"`
	POS         string       `json:"pos,omitempty"`
	Origin      string       `json:"origin"`
	Definitions []Definition `json:"definitions"`
}

type Result struct {
	Query      string      `json:"query"`
	Normalized string      `json:"normalized"`
	Language   string      `json:"language"`
	Candidates []Candidate `json:"candidates"`
}

// Normalize lowercases and strips surrounding punctuation. Kept identical to
// what the ingestion pipeline stores in tokens.normalized so that a tap and an
// ingested token resolve the same way.
func Normalize(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimFunc(s, func(r rune) bool {
		// Keep intra-word hyphens and apostrophes, drop edge punctuation.
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
	return s
}

// Lookup resolves a surface form to definitions.
//
// Order matters and is chosen so the common case is a single indexed query:
//  1. the form is already a base form present in lexemes
//  2. the form is inflected -> resolve via forms, then fetch those lemmas
//
// Both are covered by one query against the forms/lexemes indexes.
func Lookup(ctx context.Context, pool *pgxpool.Pool, lang, surface string) (Result, error) {
	norm := Normalize(surface)
	if lang == "" {
		lang = DefaultLanguage
	}
	res := Result{Query: surface, Normalized: norm, Language: lang, Candidates: []Candidate{}}
	if norm == "" {
		return res, nil
	}

	// Single round trip: direct hits on lexemes.lemma unioned with hits
	// reached through the forms table.
	rows, err := pool.Query(ctx, `
		SELECT l.lemma, COALESCE(l.pos,''), l.origin, l.definitions
		FROM lexemes l
		WHERE l.language_code = $2 AND l.lemma = $1
		UNION
		SELECT l.lemma, COALESCE(l.pos,''), l.origin, l.definitions
		FROM forms f
		JOIN lexemes l ON l.lemma = f.lemma AND l.language_code = f.language_code
		WHERE f.language_code = $2 AND f.form = $1
		ORDER BY 1, 2`, norm, lang)
	if err != nil {
		return res, err
	}
	defer rows.Close()

	for rows.Next() {
		var c Candidate
		var raw []byte
		if err := rows.Scan(&c.Lemma, &c.POS, &c.Origin, &raw); err != nil {
			return res, err
		}
		if err := json.Unmarshal(raw, &c.Definitions); err != nil {
			return res, err
		}
		res.Candidates = append(res.Candidates, c)
	}
	return res, rows.Err()
}

// ResolveLemmas returns the distinct candidate lemmas for a form, without
// definitions. Used by the ingestion lemmatize stage.
func ResolveLemmas(ctx context.Context, pool *pgxpool.Pool, lang, norm string) ([]string, error) {
	rows, err := pool.Query(ctx, `
		SELECT lemma FROM forms WHERE language_code = $2 AND form = $1
		UNION
		SELECT lemma FROM lexemes WHERE language_code = $2 AND lemma = $1`, norm, lang)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var l string
		if err := rows.Scan(&l); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}
