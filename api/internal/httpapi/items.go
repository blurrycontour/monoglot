package httpapi

import (
	"database/sql"

	"errors"
	"net/http"
	"os"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/db"
)

type ItemSummary struct {
	ID          int        `json:"id"`
	SourceSlug  string     `json:"source_slug"`
	SourceName  string     `json:"source_name"`
	Title       string     `json:"title"`
	Description string     `json:"description,omitempty"`
	PublishedAt *time.Time `json:"published_at,omitempty"`
	// When this server first saw the episode, which is what "new" means to a
	// reader: the publisher's timestamp can be hours older than the fetch, and
	// an episode that arrived while you were away is new whenever it aired.
	DiscoveredAt *time.Time `json:"discovered_at,omitempty"`
	DurationMS   int        `json:"duration_ms"`
	Status       string     `json:"status"`
	PositionMS   int        `json:"position_ms"`
	Completed    bool       `json:"completed"`
	ListenCount  int        `json:"listen_count"`
}

func (s *Server) listItems(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status == "" {
		status = "ready"
	}
	source := r.URL.Query().Get("source")
	limit := queryInt(r, "limit", 50)
	offset := queryInt(r, "offset", 0)
	if limit <= 0 || limit > 200 {
		limit = 50
	}

	// The library and its back catalogue are two views split by one fact:
	// whether the episode was ever fetched. "library" is what a reader sees in
	// date order — everything ready, plus what they fetched and later removed,
	// still in its own section and re-fetchable in place. "archived" is the
	// rest: episodes the server knows of but never fetched, revealed on demand.
	var cond string
	var condArgs []any
	switch status {
	case "library":
		cond = "(i.status = 'ready' OR (i.status = 'archived' AND i.fetched_at IS NOT NULL))"
	case "archived":
		cond = "(i.status = 'archived' AND i.fetched_at IS NULL)"
	case "all":
		cond = "1 = 1"
	default:
		cond = "i.status = ?"
		condArgs = append(condArgs, status)
	}

	args := []any{}
	args = append(args, condArgs...)
	args = append(args, source, source, limit, offset)

	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT i.id, s.slug, s.name, i.title, COALESCE(i.description,''),
		       i.published_at, i.created_at, COALESCE(i.duration_ms,0), i.status,
		       COALESCE(p.position_ms,0), COALESCE(p.completed,false),
		       COALESCE(p.listen_count,0)
		FROM items i
		JOIN sources s ON s.id = i.source_id
		LEFT JOIN progress p ON p.item_id = i.id
		WHERE `+cond+`
		  AND (? = '' OR s.slug = ?)
		ORDER BY i.published_at DESC NULLS LAST, i.id DESC
		LIMIT ? OFFSET ?`, args...)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := []ItemSummary{}
	for rows.Next() {
		var it ItemSummary
		var published, discovered db.NullTime
		if err := rows.Scan(&it.ID, &it.SourceSlug, &it.SourceName, &it.Title,
			&it.Description, &published, &discovered, &it.DurationMS, &it.Status,
			&it.PositionMS, &it.Completed, &it.ListenCount); err != nil {
			serverError(w, err)
			return
		}
		it.PublishedAt = published.Ptr()
		it.DiscoveredAt = discovered.Ptr()
		out = append(out, it)
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

type Token struct {
	ID         int    `json:"id"`
	SegmentID  int    `json:"segment_id"`
	Idx        int    `json:"idx"`
	Surface    string `json:"surface"`
	Normalized string `json:"normalized"`
	StartMS    int    `json:"start_ms"`
	EndMS      int    `json:"end_ms"`
	IsWord     bool   `json:"is_word"`
	Lemma      string `json:"lemma,omitempty"`
}

type Seg struct {
	ID      int    `json:"id"`
	Idx     int    `json:"idx"`
	StartMS int    `json:"start_ms"`
	EndMS   int    `json:"end_ms"`
	Text    string `json:"text"`
}

func (s *Server) getItem(w http.ResponseWriter, r *http.Request) {
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
	writeJSON(w, http.StatusOK, map[string]any{
		"item": item, "segments": segs, "tokens": toks,
	})
}

func (s *Server) loadItem(r *http.Request, id int) (ItemSummary, error) {
	var it ItemSummary
	var published, discovered db.NullTime
	err := s.pool.QueryRowContext(r.Context(), `
		SELECT i.id, s.slug, s.name, i.title, COALESCE(i.description,''),
		       i.published_at, i.created_at, COALESCE(i.duration_ms,0), i.status,
		       COALESCE(p.position_ms,0), COALESCE(p.completed,false),
		       COALESCE(p.listen_count,0)
		FROM items i
		JOIN sources s ON s.id = i.source_id
		LEFT JOIN progress p ON p.item_id = i.id
		WHERE i.id = ?`, id).Scan(&it.ID, &it.SourceSlug, &it.SourceName,
		&it.Title, &it.Description, &published, &discovered, &it.DurationMS,
		&it.Status, &it.PositionMS, &it.Completed, &it.ListenCount)
	it.PublishedAt = published.Ptr()
	it.DiscoveredAt = discovered.Ptr()
	return it, err
}

func (s *Server) loadSegments(r *http.Request, id int) ([]Seg, error) {
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT id, idx, start_ms, end_ms, text FROM segments
		WHERE item_id=? ORDER BY idx`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Seg{}
	for rows.Next() {
		var s Seg
		if err := rows.Scan(&s.ID, &s.Idx, &s.StartMS, &s.EndMS, &s.Text); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (s *Server) loadTokens(r *http.Request, id int) ([]Token, error) {
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT id, segment_id, idx, surface, normalized, start_ms, end_ms,
		       is_word, COALESCE(lemma,'')
		FROM tokens WHERE item_id=? ORDER BY idx`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Token{}
	for rows.Next() {
		var t Token
		if err := rows.Scan(&t.ID, &t.SegmentID, &t.Idx, &t.Surface, &t.Normalized,
			&t.StartMS, &t.EndMS, &t.IsWord, &t.Lemma); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// getAudio serves the local file. http.ServeFile handles Range requests
// correctly, which seeking and mobile playback both depend on; do not be
// tempted to write the bytes manually.
func (s *Server) getAudio(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	var path string
	err = s.pool.QueryRowContext(r.Context(),
		`SELECT COALESCE(audio_path,'') FROM items WHERE id=?`, id).Scan(&path)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		serverError(w, err)
		return
	}
	if path == "" {
		http.Error(w, "item has no downloaded audio", http.StatusNotFound)
		return
	}
	f, err := os.Stat(path)
	if err != nil {
		http.Error(w, "audio file missing on disk", http.StatusNotFound)
		return
	}
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeFile(w, r, path)
	_ = f
}

// resetProgress clears playback position for one item, so it reads as unheard
// again. Listen count is kept: it records history, not position.
func (s *Server) resetProgress(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	if _, err := s.pool.ExecContext(r.Context(), `
		UPDATE progress SET position_ms = 0, completed = 0, updated_at = strftime('%Y-%m-%d %H:%M:%S','now')
		WHERE item_id = ?`, id); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "position_ms": 0})
}

func (s *Server) postProgress(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	var body struct {
		PositionMS *int  `json:"position_ms"`
		Completed  *bool `json:"completed"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}
	pos := 0
	if body.PositionMS != nil {
		pos = *body.PositionMS
	}
	completed := body.Completed != nil && *body.Completed

	// listen_count increments only on the transition into completed, so
	// scrubbing around the end of an item does not inflate it.
	_, err = s.pool.ExecContext(r.Context(), `
		INSERT INTO progress (item_id, position_ms, completed, listen_count, updated_at)
		VALUES (?, ?, ?, CASE WHEN ? THEN 1 ELSE 0 END, strftime('%Y-%m-%d %H:%M:%S','now'))
		ON CONFLICT (item_id) DO UPDATE SET
		  position_ms  = excluded.position_ms,
		  completed    = MAX(progress.completed, excluded.completed),
		  listen_count = progress.listen_count +
		      CASE WHEN excluded.completed = 1 AND progress.completed = 0 THEN 1 ELSE 0 END,
		  updated_at   = strftime('%Y-%m-%d %H:%M:%S','now')`, id, pos, completed, completed)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ItemSummary is the end-of-episode debrief: what you actually did, rather
// than a score. The only honest measure available is how many words you had to
// look up, and which ones — those are the words the episode taught you.
type ItemSummaryResponse struct {
	ItemID      int      `json:"item_id"`
	Title       string   `json:"title"`
	DurationMs  int      `json:"duration_ms"`
	Lookups     int      `json:"lookups"`
	UniqueWords int      `json:"unique_words"`
	Words       []string `json:"words"`
}

func (s *Server) itemSummary(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad id")
		return
	}

	out := ItemSummaryResponse{ItemID: id, Words: []string{}}
	var dur sql.NullInt64
	if err := s.pool.QueryRowContext(r.Context(),
		`SELECT title, duration_ms FROM items WHERE id = ?`, id,
	).Scan(&out.Title, &dur); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		serverError(w, err)
		return
	}
	out.DurationMs = int(dur.Int64)

	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT lemma, count(*) FROM lookups
		WHERE item_id = ? GROUP BY lemma ORDER BY count(*) DESC, lemma`, id)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var lemma string
		var n int
		if err := rows.Scan(&lemma, &n); err != nil {
			serverError(w, err)
			return
		}
		out.Words = append(out.Words, lemma)
		out.Lookups += n
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	out.UniqueWords = len(out.Words)

	writeJSON(w, http.StatusOK, out)
}
