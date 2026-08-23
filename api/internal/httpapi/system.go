package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/adityasingh/svenska/api/internal/db"
	"github.com/adityasingh/svenska/api/internal/lexicon"
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

	Host HostStats `json:"host"`

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
	info.Storage.CacheBytes = dirSize(os.Getenv("DATA_CACHE_DIR"))
	if fi, err := os.Stat(s.cfg.APKPath); err == nil {
		info.Storage.APKBytes = fi.Size()
	}
	info.Storage.TotalBytes = info.Storage.AudioBytes + info.Storage.RawBytes +
		info.Storage.CacheBytes + info.Storage.APKBytes
	info.Storage.DiskFree = diskFree(s.cfg.AudioDir)
	info.Host = readHostStats(ctx)

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
