package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/adityasingh/svenska/api/internal/lexicon"
)

// Bundle is everything the client needs to play an item with no network at
// all: metadata, segments, tokens, and the full definitions for every distinct
// lemma in the item, inlined.
//
// This is what makes tap-to-define work on the bus. The client must never have
// to resolve definitions one at a time.
type Bundle struct {
	Item        ItemSummary                    `json:"item"`
	Segments    []Seg                          `json:"segments"`
	Tokens      []Token                        `json:"tokens"`
	Definitions map[string][]lexicon.Candidate `json:"definitions"`
	Attribution map[string]string              `json:"attribution"`
	Version     int                            `json:"version"`
}

func (s *Server) getBundle(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}

	item, err := s.loadItem(r, id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		serverError(w, err)
		return
	}
	segs, err := s.loadSegments(r, id)
	if err != nil {
		serverError(w, err)
		return
	}
	toks, err := s.loadTokens(r, id)
	if err != nil {
		serverError(w, err)
		return
	}

	// One query for every definition in the item. Keyed by the token's
	// normalized surface so the client can look up exactly what it renders,
	// without redoing morphology on the device.
	defs, err := s.definitionsForItem(r, id)
	if err != nil {
		serverError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, Bundle{
		Item:        item,
		Segments:    segs,
		Tokens:      toks,
		Definitions: defs,
		Version:     1,
		Attribution: attributionFor(item.SourceSlug),
	})
}

// definitionsForItem resolves every distinct normalized token in the item to
// its candidate lemmas and their definitions, in a single round trip.
func (s *Server) definitionsForItem(r *http.Request, id int) (map[string][]lexicon.Candidate, error) {
	rows, err := s.pool.QueryContext(r.Context(), `
		WITH lang AS (
		  SELECT s.language_code AS code FROM items i
		  JOIN sources s ON s.id = i.source_id WHERE i.id = ?
		), item_forms AS (
		  SELECT DISTINCT normalized FROM tokens
		  WHERE item_id = ? AND is_word AND normalized <> ''
		)
		SELECT f.normalized, l.lemma, COALESCE(l.pos,''), l.origin, l.definitions
		FROM item_forms f
		CROSS JOIN lang
		JOIN forms fm ON fm.form = f.normalized AND fm.language_code = lang.code
		JOIN lexemes l ON l.lemma = fm.lemma AND l.language_code = lang.code
		UNION
		SELECT f.normalized, l.lemma, COALESCE(l.pos,''), l.origin, l.definitions
		FROM item_forms f
		CROSS JOIN lang
		JOIN lexemes l ON l.lemma = f.normalized AND l.language_code = lang.code
		ORDER BY 1, 2, 3`, id, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[string][]lexicon.Candidate{}
	for rows.Next() {
		var norm string
		var c lexicon.Candidate
		var raw []byte
		if err := rows.Scan(&norm, &c.Lemma, &c.POS, &c.Origin, &raw); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(raw, &c.Definitions); err != nil {
			return nil, err
		}
		out[norm] = append(out[norm], c)
	}
	return out, rows.Err()
}

// attributionFor returns the credits the client must display for an item.
// Sveriges Radio requires attribution on any item sourced from SR; the
// dictionary and morphology credits are CC BY-SA obligations.
func attributionFor(sourceSlug string) map[string]string {
	a := map[string]string{
		"dictionary": "Folkets lexikon (KTH/CSC), CC BY-SA 2.5",
		"morphology": "SALDO, Språkbanken, University of Gothenburg, CC BY-SA 2.5",
		"asr":        "KB-Whisper, KBLab, National Library of Sweden",
	}
	if sourceSlug == "klartext" {
		a["source"] = "Sveriges Radio"
	}
	if sourceSlug == "8sidor" {
		a["source"] = "8 Sidor"
	}
	return a
}

func decodeJSON(r *http.Request, v any) error {
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(v); err != nil {
		return fmt.Errorf("invalid JSON body: %w", err)
	}
	return nil
}
