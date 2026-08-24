package httpapi

import (
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/db"
	"github.com/blurrycontour/monoglot/api/internal/ingest"
	"github.com/blurrycontour/monoglot/api/internal/lexicon"
)

type SourceStats struct {
	ID          int        `json:"id"`
	Slug        string     `json:"slug"`
	Name        string     `json:"name"`
	Language    string     `json:"language_code"`
	Enabled     bool       `json:"enabled"`
	LastFetched *time.Time `json:"last_fetched,omitempty"`

	Total      int   `json:"total"`      // every item ever discovered
	Ready      int   `json:"ready"`      // playable now
	Processing int   `json:"processing"` // still moving through the pipeline
	Failed     int   `json:"failed"`
	Completed  int   `json:"completed"` // listened to the end
	Started    int   `json:"started"`   // begun but not finished
	Archived   int   `json:"archived"`  // data removed, re-fetchable
	AudioBytes int64 `json:"audio_bytes"`
}

type SystemInfo struct {
	Sources []SourceStats `json:"sources"`

	Items struct {
		Total      int `json:"total"`
		Ready      int `json:"ready"`
		Processing int `json:"processing"`
		Failed     int `json:"failed"`
		Completed  int `json:"completed"`
		Started    int `json:"started"`
		Archived   int `json:"archived"`
	} `json:"items"`

	Storage struct {
		AudioBytes   int64 `json:"audio_bytes"`
		RawBytes     int64 `json:"raw_bytes"`
		CacheBytes   int64 `json:"cache_bytes"`
		APKBytes     int64 `json:"apk_bytes"`
		TotalBytes   int64 `json:"total_bytes"`
		DiskFree     int64 `json:"disk_free_bytes"`
		DatabaseSize int64 `json:"database_bytes"`
	} `json:"storage"`

	Lexicon struct {
		Lexemes int `json:"lexemes"`
		Forms   int `json:"forms"`
	} `json:"lexicon"`

	Vocabulary struct {
		Total    int `json:"total"`
		Known    int `json:"known"`
		Learning int `json:"learning"`
		Lookups  int `json:"lookups"`
	} `json:"vocabulary"`

	// Per-container CPU and memory. Machine-wide /proc figures used to sit
	// here too, but inside a container they describe the hypervisor's box, not
	// this service, and answered nothing the per-container view does not.
	Containers []ContainerStat `json:"containers"`

	Languages     []lexicon.Language `json:"languages"`
	IngestRunning bool               `json:"ingest_running"`
	ListenedMs    int64              `json:"listened_ms"`
}

// systemInfo powers the System screen: what exists, what is done, and what is
// using disk.
func (s *Server) systemInfo(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var info SystemInfo
	info.IngestRunning = s.runner.Running()

	langs, err := lexicon.Languages(ctx, s.pool)
	if err != nil {
		serverError(w, err)
		return
	}
	info.Languages = langs

	rows, err := s.pool.QueryContext(ctx, `
		SELECT s.id, s.slug, s.name, s.language_code, s.enabled, s.last_fetched,
		  count(i.id),
		  count(i.id) FILTER (WHERE i.status = 'ready'),
		  count(i.id) FILTER (WHERE i.status IN ('new','downloading','downloaded','transcribing')),
		  count(i.id) FILTER (WHERE i.status = 'failed'),
		  count(i.id) FILTER (WHERE p.completed = 1),
		  count(i.id) FILTER (WHERE p.position_ms > 0 AND COALESCE(p.completed,0) = 0),
		  count(i.id) FILTER (WHERE i.status = 'archived')
		FROM sources s
		LEFT JOIN items i ON i.source_id = s.id
		LEFT JOIN progress p ON p.item_id = i.id
		GROUP BY s.id ORDER BY s.id`)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var st SourceStats
		var fetched db.NullTime
		if err := rows.Scan(&st.ID, &st.Slug, &st.Name, &st.Language, &st.Enabled,
			&fetched, &st.Total, &st.Ready, &st.Processing, &st.Failed,
			&st.Completed, &st.Started, &st.Archived); err != nil {
			serverError(w, err)
			return
		}
		st.LastFetched = fetched.Ptr()
		info.Sources = append(info.Sources, st)
		info.Items.Total += st.Total
		info.Items.Ready += st.Ready
		info.Items.Processing += st.Processing
		info.Items.Failed += st.Failed
		info.Items.Completed += st.Completed
		info.Items.Started += st.Started
		info.Items.Archived += st.Archived
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}

	// Per-source audio footprint, so the System screen can say which source is
	// actually costing disk.
	perSource := map[int]int64{}
	arows, err := s.pool.QueryContext(ctx,
		`SELECT source_id, COALESCE(audio_path,'') FROM items WHERE audio_path IS NOT NULL`)
	if err == nil {
		for arows.Next() {
			var sid int
			var path string
			if arows.Scan(&sid, &path) == nil && path != "" {
				if fi, err := os.Stat(path); err == nil {
					perSource[sid] += fi.Size()
				}
			}
		}
		arows.Close()
	}
	for i := range info.Sources {
		info.Sources[i].AudioBytes = perSource[info.Sources[i].ID]
	}

	info.Storage.AudioBytes = dirSize(s.cfg.AudioDir)
	info.Storage.RawBytes = dirSize(s.cfg.RawDir)
	info.Storage.CacheBytes = dirSize(cacheDir())
	if fi, err := os.Stat(s.apkPath()); err == nil {
		info.Storage.APKBytes = fi.Size()
	}
	info.Storage.TotalBytes = info.Storage.AudioBytes + info.Storage.RawBytes +
		info.Storage.CacheBytes + info.Storage.APKBytes
	info.Storage.DiskFree = diskFree(s.cfg.AudioDir)
	info.Containers = readContainerStats(ctx)

	info.Storage.DatabaseSize = db.FileSize(s.cfg.DatabasePath)
	s.pool.QueryRowContext(ctx, `SELECT count(*) FROM lexemes`).Scan(&info.Lexicon.Lexemes)
	s.pool.QueryRowContext(ctx, `SELECT count(*) FROM forms`).Scan(&info.Lexicon.Forms)
	s.pool.QueryRowContext(ctx, `
		SELECT count(*),
		  count(*) FILTER (WHERE status='known'),
		  count(*) FILTER (WHERE status='learning')
		FROM user_words`).Scan(&info.Vocabulary.Total, &info.Vocabulary.Known,
		&info.Vocabulary.Learning)
	s.pool.QueryRowContext(ctx, `SELECT count(*) FROM lookups`).Scan(&info.Vocabulary.Lookups)
	s.pool.QueryRowContext(ctx, `SELECT COALESCE(sum(position_ms),0) FROM progress`).
		Scan(&info.ListenedMs)

	writeJSON(w, http.StatusOK, info)
}

// cacheDir matches bootstrap.Fetch: read from the same place, default to the
// same path. Reading it with no default here made the System screen report
// zero cache whenever the variable was absent.
func cacheDir() string {
	if v := os.Getenv("DATA_CACHE_DIR"); v != "" {
		return v
	}
	return "/data/cache"
}

func dirSize(dir string) int64 {
	if dir == "" {
		return 0
	}
	var total int64
	filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if fi, err := d.Info(); err == nil {
			total += fi.Size()
		}
		return nil
	})
	return total
}

// archiveItem frees the bulk of an item's disk use (audio, and the transcript
// rows) while keeping the item itself, so it can be re-fetched on demand.
// Deleting the row outright would let the next discover run re-add it as new,
// which is indistinguishable from never having seen it.
func (s *Server) archiveItem(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	if err := s.archive(r, id); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "status": "archived"})
}

// cancelItem takes an episode out of the pipeline.
//
// Queued items simply stop being selected. The one being transcribed right now
// cannot actually be stopped — the worker is mid-request and there is no way
// to interrupt it — so it is marked archived and the transcriber discards the
// result when it comes back rather than resurrecting the row.
func (s *Server) cancelItem(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	var status string
	if err := s.pool.QueryRowContext(r.Context(),
		`SELECT status FROM items WHERE id=?`, id).Scan(&status); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		serverError(w, err)
		return
	}
	switch status {
	case "new", "downloading", "downloaded", "transcribing", "failed":
	default:
		badRequest(w, "only an unfinished episode can be cancelled")
		return
	}

	// Ask the worker to stop before the audio is deleted: it checks between
	// segments, so it abandons the job within a second or two instead of
	// running the whole episode out to produce a result nobody wants.
	var stopped bool
	if status == "transcribing" {
		var path string
		s.pool.QueryRowContext(r.Context(),
			`SELECT COALESCE(audio_path,'') FROM items WHERE id=?`, id).Scan(&path)
		if path != "" {
			if err := ingest.CancelWorker(r.Context(), s.cfg.WorkerURL, path); err != nil {
				log.Printf("cancel item %d: worker cancel failed: %v", id, err)
			} else {
				stopped = true
			}
		}
	}

	if err := s.archive(r, id); err != nil {
		serverError(w, err)
		return
	}
	// attempts is reset so fetching it again later starts with a clean slate
	// rather than one retry away from being given up on.
	s.pool.ExecContext(r.Context(), `UPDATE items SET attempts=0, error=NULL WHERE id=?`, id)

	// Stop waiting on the worker, if this was the item it is chewing on. The
	// worker runs to completion regardless, but the pipeline was otherwise
	// parked on a result it had already decided to discard — so everything
	// else in the queue sat in 'new' until that finished.
	aborted := ingest.AbortTranscription(id)
	// And start a run, since cancelling is usually how room is made for
	// something else. A run already in flight records this and repeats.
	s.runner.Trigger("cancel")
	log.Printf("cancel item %d (was %s, worker stopped=%t, call aborted=%t)",
		id, status, stopped, aborted)
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "cancelled": status})
}

func (s *Server) archive(r *http.Request, id int) error {
	var path string
	if err := s.pool.QueryRowContext(r.Context(),
		`SELECT COALESCE(audio_path,'') FROM items WHERE id=?`, id).Scan(&path); err != nil {
		return err
	}
	if path != "" {
		os.Remove(path)
	}
	os.Remove(filepath.Join(s.cfg.RawDir, strconv.Itoa(id)+".json"))

	// Segments cascade to tokens.
	if _, err := s.pool.ExecContext(r.Context(), `DELETE FROM segments WHERE item_id=?`, id); err != nil {
		return err
	}
	_, err := s.pool.ExecContext(r.Context(), `
		UPDATE items SET status='archived', audio_path=NULL, error=NULL WHERE id=?`, id)
	return err
}

// restoreItem puts an archived item back in the queue. The pipeline state
// machine does the rest: 'new' means download, then transcribe.
func (s *Server) restoreItem(w http.ResponseWriter, r *http.Request) {
	id, err := intParam(r, "id")
	if err != nil {
		badRequest(w, "bad item id")
		return
	}
	if _, err := s.pool.ExecContext(r.Context(),
		`UPDATE items SET status='new', error=NULL WHERE id=?`, id); err != nil {
		serverError(w, err)
		return
	}
	s.runner.Trigger("restore")
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "status": "new"})
}

// cleanup archives everything older than ?days=, skipping anything currently
// in progress so a half-listened episode is never pulled out from under you.
func (s *Server) cleanup(w http.ResponseWriter, r *http.Request) {
	days := queryInt(r, "days", 30)
	if days < 1 {
		badRequest(w, "days must be at least 1")
		return
	}
	rows, err := s.pool.QueryContext(r.Context(), `
		SELECT i.id FROM items i
		LEFT JOIN progress p ON p.item_id = i.id
		WHERE i.status = 'ready'
		  AND i.published_at < datetime('now', '-' || ? || ' days')
		  AND COALESCE(p.position_ms, 0) = 0`, days)
	if err != nil {
		serverError(w, err)
		return
	}
	var ids []int
	for rows.Next() {
		var id int
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()

	archived := 0
	for _, id := range ids {
		if err := s.archive(r, id); err == nil {
			archived++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"archived": archived, "days": days})
}
