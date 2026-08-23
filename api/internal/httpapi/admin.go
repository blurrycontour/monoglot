package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/adityasingh/svenska/api/internal/lexicon"
)

type SourceRow struct {
	ID          int        `json:"id"`
	Slug        string     `json:"slug"`
	Name        string     `json:"name"`
	Kind        string     `json:"kind"`
	Enabled     bool       `json:"enabled"`
	LastFetched *time.Time `json:"last_fetched,omitempty"`
	ItemCount   int        `json:"item_count"`
}

func (s *Server) listSources(w http.ResponseWriter, r *http.Request) {
	rows, err := s.pool.Query(r.Context(), `
		SELECT s.id, s.slug, s.name, s.kind, s.enabled, s.last_fetched,
		       (SELECT count(*) FROM items i WHERE i.source_id = s.id AND i.status='ready')
		FROM sources s ORDER BY s.id`)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()
	out := []SourceRow{}
	for rows.Next() {
		var sr SourceRow
		if err := rows.Scan(&sr.ID, &sr.Slug, &sr.Name, &sr.Kind,
			&sr.Enabled, &sr.LastFetched, &sr.ItemCount); err != nil {
			serverError(w, err)
			return
		}
		out = append(out, sr)
	}
	writeJSON(w, http.StatusOK, map[string]any{"sources": out})
}

func (s *Server) setSourceEnabled(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad source id")
		return
	}
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}
	if _, err := s.pool.Exec(r.Context(),
		`UPDATE sources SET enabled=$2 WHERE id=$1`, id, body.Enabled); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "enabled": body.Enabled})
}

func (s *Server) triggerIngest(w http.ResponseWriter, r *http.Request) {
	if started := s.runner.Trigger("manual"); !started {
		writeJSON(w, http.StatusConflict,
			map[string]string{"status": "already running"})
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"status": "started"})
}

func (s *Server) listLanguages(w http.ResponseWriter, r *http.Request) {
	langs, err := lexicon.Languages(r.Context(), s.pool)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"languages": langs})
}

func contextWithTimeout(d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), d)
}

func unmarshalDefs(raw []byte, v any) {
	if len(raw) == 0 {
		return
	}
	_ = json.Unmarshal(raw, v)
}
