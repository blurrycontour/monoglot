package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/bootstrap"
	"github.com/blurrycontour/monoglot/api/internal/db"
	"github.com/blurrycontour/monoglot/api/internal/ingest"
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
	// 0..1 for the item being transcribed right now, read from the worker.
	// Absent for everything else: only one job runs at a time.
	Progress float64 `json:"progress,omitempty"`
	// Seconds the current transcription has been running.
	ElapsedSeconds float64 `json:"elapsed_seconds,omitempty"`
	// Bytes, for the download stage only: a percentage of an unknown total is
	// worse than saying how much has arrived.
	BytesDone  int64 `json:"bytes_done,omitempty"`
	BytesTotal int64 `json:"bytes_total,omitempty"`
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
	attachDownloadProgress(out.Items)
	s.attachWorkerProgress(r.Context(), out.Items)
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

// attachDownloadProgress fills in the item being fetched right now. No request
// to make: the download runs in this process, so it is a read of a mutex.
func attachDownloadProgress(items []PipelineItem) {
	state, ok := ingest.DownloadProgress()
	if !ok {
		return
	}
	for i := range items {
		if items[i].ID == state.ItemID {
			items[i].Progress = state.Fraction
			items[i].ElapsedSeconds = state.Elapsed
			items[i].BytesDone = state.Written
			items[i].BytesTotal = state.Total
			return
		}
	}
}

// attachWorkerProgress asks the worker how far into the current audio it has
// got. One short request, and only when something is actually transcribing, so
// a status poll on an idle instance costs nothing extra.
func (s *Server) attachWorkerProgress(ctx context.Context, items []PipelineItem) {
	transcribing := false
	for i := range items {
		if items[i].Status == "transcribing" {
			transcribing = true
			break
		}
	}
	if !transcribing {
		return
	}

	ctx, cancel := context.WithTimeout(ctx, 700*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimRight(s.cfg.WorkerURL, "/")+"/progress", nil)
	if err != nil {
		return
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return
	}
	var p struct {
		Path     string  `json:"path"`
		Fraction float64 `json:"fraction"`
		Elapsed  float64 `json:"elapsed"`
	}
	if json.NewDecoder(resp.Body).Decode(&p) != nil || p.Path == "" {
		return
	}

	// Match on the file, not on "the first transcribing row". The worker runs
	// one job at a time behind a lock, so while it finishes an earlier file a
	// newly started row is also 'transcribing' — and it was being shown that
	// other file's progress, appearing at 88% a second after it began.
	// Audio is written as <id>.<ext>, so the id is the filename stem.
	base := filepath.Base(p.Path)
	stem := strings.TrimSuffix(base, filepath.Ext(base))
	id, err := strconv.Atoi(stem)
	if err != nil {
		return
	}
	for i := range items {
		if items[i].ID == id && items[i].Status == "transcribing" {
			items[i].Progress = p.Fraction
			items[i].ElapsedSeconds = p.Elapsed
			return
		}
	}
}
