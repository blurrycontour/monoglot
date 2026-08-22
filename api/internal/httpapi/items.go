package httpapi

import (
	"errors"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5"
)

type ItemSummary struct {
	ID          int        `json:"id"`
	SourceSlug  string     `json:"source_slug"`
	SourceName  string     `json:"source_name"`
	Title       string     `json:"title"`
	Description string     `json:"description,omitempty"`
	PublishedAt *time.Time `json:"published_at,omitempty"`
	DurationMS  int        `json:"duration_ms"`
	Status      string     `json:"status"`
	PositionMS  int        `json:"position_ms"`
	Completed   bool       `json:"completed"`
	ListenCount int        `json:"listen_count"`
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

	rows, err := s.pool.Query(r.Context(), `
		SELECT i.id, s.slug, s.name, i.title, COALESCE(i.description,''),
		       i.published_at, COALESCE(i.duration_ms,0), i.status,
		       COALESCE(p.position_ms,0), COALESCE(p.completed,false),
		       COALESCE(p.listen_count,0)
		FROM items i
		JOIN sources s ON s.id = i.source_id
		LEFT JOIN progress p ON p.item_id = i.id
		WHERE ($1 = 'all' OR i.status = $1)
		  AND ($2 = '' OR s.slug = $2)
		ORDER BY i.published_at DESC NULLS LAST, i.id DESC
		LIMIT $3 OFFSET $4`, status, source, limit, offset)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := []ItemSummary{}
	for rows.Next() {
		var it ItemSummary
		if err := rows.Scan(&it.ID, &it.SourceSlug, &it.SourceName, &it.Title,
			&it.Description, &it.PublishedAt, &it.DurationMS, &it.Status,
			&it.PositionMS, &it.Completed, &it.ListenCount); err != nil {
			serverError(w, err)
			return
		}
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
		if errors.Is(err, pgx.ErrNoRows) {
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
	err := s.pool.QueryRow(r.Context(), `
		SELECT i.id, s.slug, s.name, i.title, COALESCE(i.description,''),
		       i.published_at, COALESCE(i.duration_ms,0), i.status,
		       COALESCE(p.position_ms,0), COALESCE(p.completed,false),
		       COALESCE(p.listen_count,0)
		FROM items i
		JOIN sources s ON s.id = i.source_id
		LEFT JOIN progress p ON p.item_id = i.id
		WHERE i.id = $1`, id).Scan(&it.ID, &it.SourceSlug, &it.SourceName,
		&it.Title, &it.Description, &it.PublishedAt, &it.DurationMS, &it.Status,
		&it.PositionMS, &it.Completed, &it.ListenCount)
	return it, err
}

func (s *Server) loadSegments(r *http.Request, id int) ([]Seg, error) {
	rows, err := s.pool.Query(r.Context(), `
		SELECT id, idx, start_ms, end_ms, text FROM segments
		WHERE item_id=$1 ORDER BY idx`, id)
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
	rows, err := s.pool.Query(r.Context(), `
		SELECT id, segment_id, idx, surface, normalized, start_ms, end_ms,
		       is_word, COALESCE(lemma,'')
		FROM tokens WHERE item_id=$1 ORDER BY idx`, id)
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
	err = s.pool.QueryRow(r.Context(),
		`SELECT COALESCE(audio_path,'') FROM items WHERE id=$1`, id).Scan(&path)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
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
	_, err = s.pool.Exec(r.Context(), `
		INSERT INTO progress (item_id, position_ms, completed, listen_count, updated_at)
		VALUES ($1, $2, $3, CASE WHEN $3 THEN 1 ELSE 0 END, now())
		ON CONFLICT (item_id) DO UPDATE SET
		  position_ms  = EXCLUDED.position_ms,
		  completed    = progress.completed OR EXCLUDED.completed,
		  listen_count = progress.listen_count +
		      CASE WHEN EXCLUDED.completed AND NOT progress.completed THEN 1 ELSE 0 END,
		  updated_at   = now()`, id, pos, completed)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
