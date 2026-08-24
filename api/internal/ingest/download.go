package ingest

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"database/sql"
)

// DeferOutOfWindow marks items outside their source's auto-download window as
// archived rather than downloading them.
//
// Archived is exactly the right state: it already means "we know about this,
// the audio is not on disk, and it can be fetched on demand". Leaving them as
// 'new' instead would have the pipeline retry them forever and the app report
// them as perpetually processing.
func DeferOutOfWindow(ctx context.Context, pool *sql.DB) error {
	tag, err := pool.ExecContext(ctx, `
		WITH ranked AS (
		  SELECT i.id, i.status,
		         row_number() OVER (
		           PARTITION BY i.source_id
		           ORDER BY i.published_at DESC NULLS LAST, i.id DESC
		         ) AS rn,
		         s.auto_download_limit AS lim
		  FROM items i
		  JOIN sources s ON s.id = i.source_id
		  -- Rank across everything already fetched, not just the new rows.
		  -- Ranking new rows alone made the window mean "ten more each time
		  -- discovery reaches further back", which is not what a limit is.
		  WHERE i.status <> 'archived'
		)
		UPDATE items SET status = 'archived'
		WHERE id IN (SELECT id FROM ranked WHERE rn > lim AND status = 'new')`)
	if err != nil {
		return err
	}
	n, _ := tag.RowsAffected()
	if n > 0 {
		log.Printf("download: deferred %d item(s) outside the auto-download window", n)
	}
	return nil
}

// DownloadPending moves every item in status='new' through to 'downloaded'.
func DownloadPending(ctx context.Context, pool *sql.DB, audioDir string, limit int) error {
	if err := DeferOutOfWindow(ctx, pool); err != nil {
		return err
	}
	rows, err := pool.QueryContext(ctx, `
		SELECT id, audio_url FROM items
		WHERE status = 'new' AND audio_url IS NOT NULL AND audio_url <> ''
		ORDER BY published_at DESC NULLS LAST
		LIMIT ?`, limit)
	if err != nil {
		return err
	}
	type job struct {
		id  int
		url string
	}
	var jobs []job
	for rows.Next() {
		var j job
		if err := rows.Scan(&j.id, &j.url); err != nil {
			rows.Close()
			return err
		}
		jobs = append(jobs, j)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	for _, j := range jobs {
		if err := downloadItem(ctx, pool, audioDir, j.id, j.url); err != nil {
			log.Printf("ERROR download item %d: %v", j.id, err)
			markFailed(ctx, pool, j.id, err)
		}
	}
	return nil
}

func downloadItem(ctx context.Context, pool *sql.DB, audioDir string, id int, url string) error {
	if _, err := pool.ExecContext(ctx,
		`UPDATE items SET status='downloading', error=NULL WHERE id=?`, id); err != nil {
		return err
	}
	if err := os.MkdirAll(audioDir, 0o755); err != nil {
		return err
	}

	dest := filepath.Join(audioDir, fmt.Sprintf("%d%s", id, extFor(url)))
	// Download to a temp file and rename, so a crash mid-transfer can never
	// leave a truncated file that looks complete on the next run.
	tmp := dest + ".part"

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "monoglot/1.0 (personal use)")

	client := &http.Client{Timeout: 15 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("audio fetch: status %d", resp.StatusCode)
	}

	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	// Content-Length is what makes the fraction meaningful; SR and Acast both
	// send it. Without it the app shows an indeterminate bar.
	startDownload(id, resp.ContentLength)
	defer endDownload()

	n, err := io.Copy(countingWriter{f}, resp.Body)
	closeErr := f.Close()
	if err != nil {
		os.Remove(tmp)
		return err
	}
	if closeErr != nil {
		os.Remove(tmp)
		return closeErr
	}
	if n == 0 {
		os.Remove(tmp)
		return fmt.Errorf("audio fetch: empty body")
	}
	if err := os.Rename(tmp, dest); err != nil {
		return err
	}

	durMS, err := probeDurationMS(ctx, dest)
	if err != nil {
		// Duration is nice to have, not fatal: the player reads it from the
		// audio element anyway. Log and continue.
		log.Printf("WARNING probe duration item %d: %v", id, err)
	}

	var dur any
	if durMS > 0 {
		dur = durMS
	}
	_, err = pool.ExecContext(ctx, `
		UPDATE items SET status='downloaded', audio_path=?,
		       duration_ms=COALESCE(?, duration_ms), error=NULL
		WHERE id=?`, dest, dur, id)
	if err != nil {
		return err
	}
	log.Printf("download item %d: %s (%d bytes)", id, dest, n)
	return nil
}

// probeDurationMS shells out to ffprobe. Milliseconds as an integer: the spec
// is emphatic that float timestamps cause off-by-one highlight bugs.
func probeDurationMS(ctx context.Context, path string) (int, error) {
	cmd := exec.CommandContext(ctx, "ffprobe",
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		path)
	out, err := cmd.Output()
	if err != nil {
		return 0, err
	}
	secs, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil {
		return 0, err
	}
	return int(secs * 1000), nil
}

func extFor(url string) string {
	base := url
	if i := strings.IndexAny(base, "?#"); i >= 0 {
		base = base[:i]
	}
	ext := strings.ToLower(filepath.Ext(base))
	switch ext {
	case ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav":
		return ext
	}
	return ".mp3"
}

// markFailed records a stage failure, but never over a terminal state.
//
// Without the status guard a cancelled item was written back as 'failed', and
// the retry logic then treated it as work to redo: cancelling did not stick.
func markFailed(ctx context.Context, pool *sql.DB, id int, cause error) {
	if _, err := pool.ExecContext(ctx,
		`UPDATE items SET status='failed', error=?
		 WHERE id=? AND status NOT IN ('archived','ready')`,
		cause.Error(), id); err != nil {
		log.Printf("ERROR marking item %d failed: %v", id, err)
	}
}
