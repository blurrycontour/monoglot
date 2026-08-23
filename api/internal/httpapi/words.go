package httpapi

import (
	"database/sql"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/adityasingh/svenska/api/internal/db"
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
	if _, err := s.pool.ExecContext(ctx,
		`INSERT INTO lookups (item_id, token_id, lemma) VALUES (?,?,?)`,
		itemArg, tokenArg, lemma); err != nil {
		return
	}
	// Touch the vocabulary row so the Words screen reflects real usage.
	s.pool.ExecContext(ctx, `
		INSERT INTO user_words (language_code, lemma, status, lookup_count)
		VALUES (?, ?, 'unknown', 1)
		ON CONFLICT (language_code, lemma) DO UPDATE SET
		  lookup_count = user_words.lookup_count + 1,
		  last_seen = strftime('%Y-%m-%d %H:%M:%S','now')`, lang, lemma)
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
	_, err := s.pool.ExecContext(r.Context(), `
		INSERT INTO user_words (language_code, lemma, status) VALUES (?,?,?)
		ON CONFLICT (language_code, lemma) DO UPDATE SET
		  status=excluded.status, last_seen=strftime('%Y-%m-%d %H:%M:%S','now')`,
		lang, lemma, body.Status)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"lemma": lemma, "status": body.Status})
}

// deleteWords removes vocabulary entries entirely. Marking a word known or
// learning is a judgement about the word; deleting is for words that should
// never have been recorded, usually an accidental tap.
func (s *Server) deleteWords(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Lemmas []string `json:"lemmas"`
		All    bool     `json:"all"`
		Status string   `json:"status"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}
	lang := langOr(r)

	var tag sql.Result
	var err error
	var deleted int64

	switch {
	case len(body.Lemmas) > 0:
		// json_each turns a JSON array bound as one parameter into rows, which
		// avoids building a variadic IN list.
		blob, mErr := json.Marshal(body.Lemmas)
		if mErr != nil {
			badRequest(w, "invalid lemmas")
			return
		}
		tag, err = s.pool.ExecContext(r.Context(),
			`DELETE FROM user_words
			 WHERE language_code = ? AND lemma IN (SELECT value FROM json_each(?))`,
			lang, string(blob))
	case body.All && body.Status != "":
		tag, err = s.pool.ExecContext(r.Context(),
			`DELETE FROM user_words WHERE language_code = ? AND status = ?`,
			lang, body.Status)
	case body.All:
		tag, err = s.pool.ExecContext(r.Context(),
			`DELETE FROM user_words WHERE language_code = ?`, lang)
	default:
		badRequest(w, "provide lemmas, or all with an optional status")
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	if tag != nil {
		if n, aErr := tag.RowsAffected(); aErr == nil {
			deleted = n
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": deleted})
}

// deleteWord removes a single lemma.
func (s *Server) deleteWord(w http.ResponseWriter, r *http.Request) {
	lemma := strings.TrimSpace(chi.URLParam(r, "lemma"))
	if lemma == "" {
		badRequest(w, "missing lemma")
		return
	}
	if _, err := s.pool.ExecContext(r.Context(),
		`DELETE FROM user_words WHERE language_code = ? AND lemma = ?`,
		langOr(r), lemma); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"deleted": lemma})
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
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT uw.lemma, uw.status, uw.lookup_count, uw.first_seen, uw.last_seen,
		       COALESCE((SELECT l.definitions FROM lexemes l
		                 WHERE l.lemma = uw.lemma AND l.language_code = uw.language_code
		                 ORDER BY l.id LIMIT 1), '[]')
		FROM user_words uw
		WHERE (? = '' OR uw.status = ?) AND uw.language_code = ?
		ORDER BY uw.last_seen DESC`, status, status, langOr(r))
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := []WordRow{}
	for rows.Next() {
		var wr WordRow
		var raw []byte
		var first, last db.NullTime
		if err := rows.Scan(&wr.Lemma, &wr.Status, &wr.LookupCount,
			&first, &last, &raw); err != nil {
			serverError(w, err)
			return
		}
		wr.FirstSeen, wr.LastSeen = first.Time, last.Time
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
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT uw.lemma, uw.status, uw.lookup_count,
		       COALESCE((SELECT l.definitions FROM lexemes l
		                 WHERE l.lemma = uw.lemma AND l.language_code = uw.language_code
		                 ORDER BY l.id LIMIT 1), '[]')
		FROM user_words uw
		WHERE (? = 'all' OR uw.status = ?) AND uw.language_code = ?
		ORDER BY uw.lookup_count DESC, uw.lemma`, status, status, langOr(r))
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename="monoglot-%s-%s.csv"`,
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
