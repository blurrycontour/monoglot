package httpapi

import (
	"net/http"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/bootstrap"
	"github.com/blurrycontour/monoglot/api/internal/db"
)

// PipelineStatus lets the app show what the server is still working on, so a
// fresh install is usable immediately instead of looking empty while the slow
// transcription stage runs.
type PipelineStatus struct {
	Counts     map[string]int `json:"counts"`
	Ready      int            `json:"ready"`
	Processing int            `json:"processing"`
	Failed     int            `json:"failed"`
	Archived   int            `json:"archived"`
	// Sub-counts so the app can show real progress rather than a spinner.
	Queued        int  `json:"queued"` // downloaded, waiting for the worker
	Transcribing  int  `json:"transcribing"`
	Downloading   int  `json:"downloading"` // new or mid-download
	IngestRunning bool `json:"ingest_running"`
	// First-run state. A new instance spends several minutes importing a
	// dictionary before it can define anything, and the app needs to say so
	// rather than showing an empty library.
	Bootstrap bootstrap.Status `json:"bootstrap"`
	// What is actually in flight, so the app can show which episodes are
	// waiting rather than only how many. Capped: the point is a glance at the
	// queue, not a second copy of the library.
	Items []PipelineItem `json:"items"`
}

type PipelineItem struct {
	ID         int    `json:"id"`
	SourceSlug string `json:"source_slug"`
	Title      string `json:"title"`
	// A *time.Time, not the raw column: SQLite stores 'YYYY-MM-DD HH:MM:SS',
	// which is not what the client parses. Every other timestamp in this API
	// is RFC3339 and this one silently was not, so every row in the queue
	// sheet rendered as a dash.
	PublishedAt *time.Time `json:"published_at,omitempty"`
	Status      string     `json:"status"`
	Attempts    int        `json:"attempts"`
	Error       string     `json:"error,omitempty"`
}

func (s *Server) pipelineStatus(w http.ResponseWriter, r *http.Request) {
	// Scoped to one source when asked. The banner sits under the source chips,
	// so counting every source under a chip that selects one was simply wrong.
	source := r.URL.Query().Get("source")

	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT i.status, count(*)
		FROM items i JOIN sources s ON s.id = i.source_id
		WHERE (? = '' OR s.slug = ?)
		GROUP BY i.status`, source, source)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := PipelineStatus{
		Counts:        map[string]int{},
		IngestRunning: s.runner.Running(),
		Bootstrap:     bootstrap.Get(),
	}
	for rows.Next() {
		var status string
		var n int
		if err := rows.Scan(&status, &n); err != nil {
			serverError(w, err)
			return
		}
		out.Counts[status] = n
		switch status {
		case "ready":
			out.Ready = n
		case "failed":
			out.Failed = n
		case "archived":
			// Deliberately deferred, not in flight. Counting these as
			// processing made a 175-episode archive read as "preparing 189
			// episodes" forever.
			out.Archived = n
		case "downloaded":
			out.Queued = n
			out.Processing += n
		case "transcribing":
			out.Transcribing = n
			out.Processing += n
		default:
			// new, downloading: fetching audio.
			out.Downloading += n
			out.Processing += n
		}
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}

	out.Items, err = s.pipelineItems(r, source)
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

// pipelineItems lists what has not finished, with whatever the last failure
// said. Ordered by stage so the thing being worked on right now is at the top.
func (s *Server) pipelineItems(r *http.Request, source string) ([]PipelineItem, error) {
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT i.id, s.slug, i.title, i.published_at, i.status,
		       i.attempts, COALESCE(i.error,'')
		FROM items i JOIN sources s ON s.id = i.source_id
		WHERE i.status IN ('new','downloading','downloaded','transcribing','failed')
		  AND (? = '' OR s.slug = ?)
		ORDER BY CASE i.status
		           WHEN 'transcribing' THEN 0
		           WHEN 'downloading'  THEN 1
		           WHEN 'downloaded'   THEN 2
		           WHEN 'new'          THEN 3
		           ELSE 4 END,
		         i.published_at DESC
		LIMIT 60`, source, source)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []PipelineItem{}
	for rows.Next() {
		var it PipelineItem
		var published db.NullTime
		if err := rows.Scan(&it.ID, &it.SourceSlug, &it.Title, &published,
			&it.Status, &it.Attempts, &it.Error); err != nil {
			return nil, err
		}
		if published.Valid {
			t := published.Time
			it.PublishedAt = &t
		}
		out = append(out, it)
	}
	return out, rows.Err()
}
