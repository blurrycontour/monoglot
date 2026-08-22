package lexicon

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"os"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
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
func ImportSaldo(ctx context.Context, pool *pgxpool.Pool, path string) error {
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
					batch = append(batch, []any{form, lemma, nullable(pos)})
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
	log.Printf("saldo: done, %d forms from %d entries", total, entries)
	return nil
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

// CopyForms bulk-loads rows of (form, lemma, pos) idempotently. pgx CopyFrom is
// far faster than INSERT for a dataset this size but cannot express ON CONFLICT,
// so stage into an unlogged temp table and merge from there.
func CopyForms(ctx context.Context, pool *pgxpool.Pool, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	// Must run inside an explicit transaction: outside one, every statement
	// commits on its own and an ON COMMIT DROP temp table would vanish before
	// the merge could see it.
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `CREATE TEMP TABLE forms_stage
		(form TEXT, lemma TEXT, pos TEXT) ON COMMIT DROP`); err != nil {
		return err
	}
	if _, err := tx.CopyFrom(ctx,
		pgx.Identifier{"forms_stage"},
		[]string{"form", "lemma", "pos"},
		pgx.CopyFromRows(rows),
	); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO forms (form, lemma, pos)
		SELECT DISTINCT form, lemma, pos FROM forms_stage
		ON CONFLICT DO NOTHING`); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func copyLexemes(ctx context.Context, pool *pgxpool.Pool, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `CREATE TEMP TABLE lexemes_stage
		(lemma TEXT, pos TEXT, definitions JSONB, origin TEXT) ON COMMIT DROP`); err != nil {
		return err
	}
	if _, err := tx.CopyFrom(ctx,
		pgx.Identifier{"lexemes_stage"},
		[]string{"lemma", "pos", "definitions", "origin"},
		pgx.CopyFromRows(rows),
	); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO lexemes (lemma, pos, definitions, origin)
		SELECT lemma, pos, definitions, origin FROM lexemes_stage
		ON CONFLICT (lemma, COALESCE(pos, ''), origin)
		DO UPDATE SET definitions = EXCLUDED.definitions`); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
