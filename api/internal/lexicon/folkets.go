package lexicon

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"os"
	"strings"

	"database/sql"
)

// FolketsURL is the public Swedish->English dump. CC BY-SA 2.5; attribution is
// shown on the About screen.
const FolketsURL = "https://folkets-lexikon.csc.kth.se/folkets/folkets_sv_en_public.xml"

// Definition is one sense of a lemma, as stored in lexemes.definitions.
type Definition struct {
	Translation string `json:"translation"`
	Comment     string `json:"comment,omitempty"`
	Example     string `json:"example,omitempty"`
}

// The Folkets schema is custom: every field is an attribute named "value",
// with an optional "comment". Inspected against the real file before writing.
type folketsWord struct {
	Value   string `xml:"value,attr"`
	Class   string `xml:"class,attr"`
	Comment string `xml:"comment,attr"`

	Translations []struct {
		Value   string `xml:"value,attr"`
		Comment string `xml:"comment,attr"`
	} `xml:"translation"`

	Definition struct {
		Value string `xml:"value,attr"`
	} `xml:"definition"`

	Examples []struct {
		Value string `xml:"value,attr"`
	} `xml:"example"`

	Paradigm struct {
		Inflections []struct {
			Value string `xml:"value,attr"`
		} `xml:"inflection"`
	} `xml:"paradigm"`
}

// ImportFolkets parses the Folkets XML into lexemes, and additionally harvests
// its <paradigm><inflection> lists into forms. Folkets covers ~19k paradigms;
// SALDO covers far more, but this makes the dictionary useful on its own.
func ImportFolkets(ctx context.Context, pool *sql.DB, lang, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	dec := xml.NewDecoder(f)
	// The dump is UTF-8 but declares entities Go's decoder doesn't know.
	dec.Strict = false

	type key struct{ lemma, pos string }
	senses := map[key][]Definition{}
	var formRows [][]any
	seenForm := map[string]bool{}

	words := 0
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("folkets xml: %w", err)
		}
		se, ok := tok.(xml.StartElement)
		if !ok || se.Name.Local != "word" {
			continue
		}
		var w folketsWord
		if err := dec.DecodeElement(&w, &se); err != nil {
			return err
		}
		lemma := strings.TrimSpace(w.Value)
		if lemma == "" {
			continue
		}
		words++

		example := ""
		if len(w.Examples) > 0 {
			example = w.Examples[0].Value
		}
		k := key{lemma, w.Class}
		for _, t := range w.Translations {
			if strings.TrimSpace(t.Value) == "" {
				continue
			}
			comment := t.Comment
			if comment == "" {
				comment = w.Comment
			}
			if comment == "" {
				comment = w.Definition.Value
			}
			senses[k] = append(senses[k], Definition{
				Translation: unescapeEntities(t.Value),
				Comment:     unescapeEntities(comment),
				Example:     unescapeEntities(example),
			})
		}

		// Inflected forms -> this lemma.
		for _, inf := range w.Paradigm.Inflections {
			form := strings.ToLower(strings.TrimSpace(inf.Value))
			if form == "" || strings.ContainsAny(form, " \t") {
				continue
			}
			dedupe := form + "\x00" + lemma + "\x00" + w.Class
			if seenForm[dedupe] {
				continue
			}
			seenForm[dedupe] = true
			formRows = append(formRows, []any{lang, form, lemma, nullable(w.Class)})
		}
	}

	lexRows := make([][]any, 0, len(senses))
	for k, defs := range senses {
		blob, err := json.Marshal(defs)
		if err != nil {
			return err
		}
		lexRows = append(lexRows, []any{lang, k.lemma, nullable(k.pos), string(blob), "folkets"})
	}

	if err := copyLexemes(ctx, pool, lexRows); err != nil {
		return err
	}
	if err := CopyForms(ctx, pool, formRows); err != nil {
		return err
	}
	log.Printf("folkets: %d words -> %d lexemes, %d forms (%s)", words, len(lexRows), len(formRows), lang)
	return nil
}

// FolketsProvider adapts the Folkets importer to the DictionaryProvider
// interface so a second language only has to supply its own implementation.
type FolketsProvider struct{}

func (FolketsProvider) SourceURL() string { return FolketsURL }
func (FolketsProvider) CacheName() string { return "folkets.xml" }
func (FolketsProvider) Attribution() string {
	return "Folkets lexikon (KTH/CSC), CC BY-SA 2.5"
}
func (FolketsProvider) Import(ctx context.Context, pool *sql.DB, lang, path string) error {
	return ImportFolkets(ctx, pool, lang, path)
}

// unescapeEntities undoes Folkets' double-encoded entities. The dump contains
// literal "&amp;quot;" which the XML decoder turns into "&quot;", so a second
// pass is needed before the text is fit to display.
func unescapeEntities(s string) string {
	if !strings.Contains(s, "&") {
		return s
	}
	r := strings.NewReplacer(
		"&quot;", `"`,
		"&#39;", "'",
		"&apos;", "'",
		"&lt;", "<",
		"&gt;", ">",
		"&amp;", "&",
	)
	return r.Replace(s)
}

func nullable(s string) any {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	return s
}
