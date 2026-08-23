package lexicon

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
)

// DefaultLanguage is used wherever the caller has no better information. The
// app is single-language today; every query is still scoped by code so that
// adding a second language is a matter of registering providers, not of
// migrating data.
const DefaultLanguage = "sv"

// Language describes one learnable language and where its lexical data comes
// from.
type Language struct {
	Code       string `json:"code"`
	Name       string `json:"name"`
	NativeName string `json:"native_name"`
	ASRCode    string `json:"asr_code"`
	Enabled    bool   `json:"enabled"`
}

// DictionaryProvider imports a bilingual dictionary for one language.
type DictionaryProvider interface {
	// SourceURL is where the dataset is downloaded from.
	SourceURL() string
	// CacheName is the local filename for the cached dataset.
	CacheName() string
	// Import parses the dataset at path into lexemes (and any inflections it
	// happens to carry) for the given language.
	Import(ctx context.Context, pool *pgxpool.Pool, lang, path string) error
	// Attribution is the licence credit that must be displayed.
	Attribution() string
}

// MorphologyProvider imports full-form to lemma mappings for one language.
type MorphologyProvider interface {
	SourceURL() string
	CacheName() string
	Import(ctx context.Context, pool *pgxpool.Pool, lang, path string) error
	Attribution() string
}

// providers is the registry. A new language means registering an
// implementation here; nothing else in the codebase needs to know.
var (
	dictionaries = map[string]DictionaryProvider{}
	morphologies = map[string]MorphologyProvider{}
)

func RegisterDictionary(lang string, p DictionaryProvider) { dictionaries[lang] = p }
func RegisterMorphology(lang string, p MorphologyProvider) { morphologies[lang] = p }

func Dictionary(lang string) (DictionaryProvider, bool) {
	p, ok := dictionaries[lang]
	return p, ok
}

func Morphology(lang string) (MorphologyProvider, bool) {
	p, ok := morphologies[lang]
	return p, ok
}

// Languages lists what the instance is configured for.
func Languages(ctx context.Context, pool *pgxpool.Pool) ([]Language, error) {
	rows, err := pool.Query(ctx,
		`SELECT code, name, COALESCE(native_name,''), asr_code, enabled
		 FROM languages ORDER BY code`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Language{}
	for rows.Next() {
		var l Language
		if err := rows.Scan(&l.Code, &l.Name, &l.NativeName, &l.ASRCode, &l.Enabled); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// ASRCode returns the transcription language hint for a content item.
func ASRCode(ctx context.Context, pool *pgxpool.Pool, itemID int) string {
	var code string
	err := pool.QueryRow(ctx, `
		SELECT l.asr_code
		FROM items i
		JOIN sources s ON s.id = i.source_id
		JOIN languages l ON l.code = s.language_code
		WHERE i.id = $1`, itemID).Scan(&code)
	if err != nil || code == "" {
		return DefaultLanguage
	}
	return code
}
