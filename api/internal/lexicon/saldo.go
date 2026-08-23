package lexicon

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"os"
	"strings"

	"database/sql"
)

// SaldoURL is the downloadable saldom dataset. The SALDO *web service* was shut
// down in 2021; only this dump is usable, which is what we want anyway since
// local lookup is what makes tap-to-define instant and offline-capable.
// CC BY-SA 2.5 / LGPL 3.0 - attribute Spraakbanken, University of Gothenburg.
const SaldoURL = "https://svn.spraakbanken.gu.se/sb-arkiv/pub/lmf/saldom/saldom.xml"

// ImportSaldo streams the ~250MB saldom LMF file into forms. It must stream:
// loading the whole document would cost gigabytes of resident memory.
//
// Multiword lemmas (POS suffixed "m": vbm, nnm, pnm, ...) are skipped. They
// balloon the table and are pure noise for tap-to-define, which only ever
// looks up a single tapped token: "gick" should offer "ga", not also
// "ga av stapeln", "ga bet", "ga bort" and forty other idioms.
func ImportSaldo(ctx context.Context, pool *sql.DB, lang, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	dec := xml.NewDecoder(f)
	dec.Strict = false

	var (
		lemma, pos string
		inLemma    bool
		batch      [][]any
		total      int
		entries    int
	)

	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		if err := CopyForms(ctx, pool, batch); err != nil {
			return err
		}
		total += len(batch)
		batch = batch[:0]
		log.Printf("saldo: %d forms imported (%d entries)", total, entries)
		return nil
	}

	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("saldom xml: %w", err)
		}

		switch el := tok.(type) {
		case xml.StartElement:
			switch el.Name.Local {
			case "Lemma":
				inLemma = true
				lemma, pos = "", ""
			case "LexicalEntry":
				entries++
			case "feat":
				att, val := attr(el, "att"), attr(el, "val")
				if inLemma {
					switch att {
					case "writtenForm":
						lemma = val
					case "partOfSpeech":
						pos = val
					}
				} else if att == "writtenForm" {
					// A WordForm surface form for the current lemma.
					if lemma == "" || skipLemma(lemma, pos) {
						continue
					}
					form := strings.ToLower(strings.TrimSpace(val))
					if form == "" || strings.ContainsAny(form, " \t") {
						continue
					}
					batch = append(batch, []any{lang, form, lemma, nullable(pos)})
					if len(batch) >= 50000 {
						if err := flush(); err != nil {
							return err
						}
					}
				}
			}
		case xml.EndElement:
			if el.Name.Local == "Lemma" {
				inLemma = false
			}
		}
	}
	if err := flush(); err != nil {
		return err
	}
	log.Printf("saldo: done, %d forms from %d entries (%s)", total, entries, lang)
	return nil
}

// SaldoProvider adapts the SALDO importer to the MorphologyProvider interface.
type SaldoProvider struct{}

func (SaldoProvider) SourceURL() string { return SaldoURL }
func (SaldoProvider) CacheName() string { return "saldom.xml" }
func (SaldoProvider) Attribution() string {
	return "SALDO, Språkbanken, University of Gothenburg, CC BY-SA 2.5"
}
func (SaldoProvider) Import(ctx context.Context, pool *sql.DB, lang, path string) error {
	return ImportSaldo(ctx, pool, lang, path)
}

// skipLemma drops multiword entries. SALDO marks these with a trailing "m" on
// the part of speech, but check for a space too since the tag is not perfectly
// consistent across the dump.
func skipLemma(lemma, pos string) bool {
	if strings.ContainsAny(lemma, " \t-") && strings.Contains(lemma, " ") {
		return true
	}
	return strings.HasSuffix(pos, "m") && pos != "m"
}

func attr(el xml.StartElement, name string) string {
	for _, a := range el.Attr {
		if a.Name.Local == name {
			return a.Value
		}
	}
	return ""
}

// CopyForms bulk-loads rows of (lang, form, lemma, pos) idempotently.
//
// SQLite has no COPY, so this is a prepared statement reused inside one
// transaction. That is the important part: a commit per row would take minutes
// for SALDO's 1.6M rows, while a single transaction takes seconds.
func CopyForms(ctx context.Context, conn *sql.DB, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback() //nolint:errcheck

	stmt, err := tx.PrepareContext(ctx, `
		INSERT INTO forms (language_code, form, lemma, pos)
		VALUES (?, ?, ?, ?)
		ON CONFLICT DO NOTHING`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, r := range rows {
		if _, err := stmt.ExecContext(ctx, r...); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func copyLexemes(ctx context.Context, conn *sql.DB, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback() //nolint:errcheck

	// The conflict target has to name the same expressions as the unique
	// index, COALESCE included, or SQLite will not match it.
	stmt, err := tx.PrepareContext(ctx, `
		INSERT INTO lexemes (language_code, lemma, pos, definitions, origin)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT (language_code, lemma, COALESCE(pos, ''), origin)
		DO UPDATE SET definitions = excluded.definitions`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, r := range rows {
		if _, err := stmt.ExecContext(ctx, r...); err != nil {
			return err
		}
	}
	return tx.Commit()
}
