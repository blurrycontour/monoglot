package ingest

import (
	"context"
	"log"
	"sync"
	"time"

	"database/sql"

	"github.com/blurrycontour/monoglot/api/internal/config"
)

// Runner serialises pipeline execution. Only one run may be in flight at a
// time: transcription is CPU-bound and overlapping runs would thrash.
type Runner struct {
	pool *sql.DB
	cfg  config.Config

	mu      sync.Mutex
	running bool
	// Set when a trigger arrives mid-run. The trigger cannot start a second
	// run — transcription must stay serial — but dropping it silently meant
	// work queued during a run waited for the watchdog.
	rerun bool
}

func NewRunner(pool *sql.DB, cfg config.Config) *Runner {
	return &Runner{pool: pool, cfg: cfg}
}

func (r *Runner) Running() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// Trigger starts a run in the background. Returns false if one is already
// going — in which case the request is remembered and honoured as soon as the
// current run finishes, rather than dropped.
func (r *Runner) Trigger(reason string) bool {
	r.mu.Lock()
	if r.running {
		r.rerun = true
		r.mu.Unlock()
		return false
	}
	r.running = true
	r.rerun = false
	r.mu.Unlock()

	go func() {
		for {
			// Detached from any request context: a manual trigger must not be
			// cancelled when the HTTP client hangs up.
			ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
			r.Run(ctx, reason)
			cancel()

			r.mu.Lock()
			again := r.rerun
			r.rerun = false
			if !again {
				r.running = false
				r.mu.Unlock()
				return
			}
			r.mu.Unlock()
			reason = "queued during the previous run"
		}
	}()
	return true
}

func (r *Runner) Run(ctx context.Context, reason string) {
	start := time.Now()
	log.Printf("ingest: run start (%s)", reason)

	// Reclaim anything stranded mid-stage by a crash, a restart, or a bug.
	// The spec's whole point in modelling this as a state machine is that any
	// stage can fail and be retried; without this, an interrupted download
	// sits in 'downloading' forever and no stage will ever pick it up.
	if err := ReclaimStuck(ctx, r.pool); err != nil {
		log.Printf("ERROR ingest: reclaiming stuck items: %v", err)
	}

	if err := Discover(ctx, r.pool); err != nil {
		log.Printf("ERROR ingest: discover: %v", err)
	}

	// Download and transcription alternate, one transcription at a time.
	//
	// The stages used to run strictly in sequence — download everything, then
	// drain the transcription queue — so anything queued after the run had
	// passed the download stage sat in 'new' behind every remaining
	// transcription, and then behind the watchdog's five-minute tick.
	// Downloading is I/O in seconds; there is no reason for it to wait on
	// minutes of CPU.
	for cycle := 0; cycle < maxCycles; cycle++ {
		downloadable, err := countDownloadable(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting downloadable: %v", err)
			break
		}
		pending, err := countPending(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting pending: %v", err)
			break
		}
		if downloadable == 0 && pending == 0 {
			log.Printf("ingest: queue empty")
			break
		}
		log.Printf("ingest: %d to download, %d to transcribe (cycle %d)",
			downloadable, pending, cycle+1)

		if downloadable > 0 {
			if err := DownloadPending(ctx, r.pool, r.cfg.AudioDir, 40); err != nil {
				log.Printf("ERROR ingest: download: %v", err)
			}
		}
		if pending > 0 {
			// One item per cycle, and re-selected each time: a batch picked up
			// front goes stale the moment anything is cancelled.
			if err := TranscribePending(ctx, r.pool, r.cfg.WorkerURL, r.cfg.RawDir, 1); err != nil {
				log.Printf("ERROR ingest: transcribe: %v", err)
				break
			}
		}
		if ctx.Err() != nil {
			log.Printf("ingest: stopping, context ended: %v", ctx.Err())
			break
		}

		// Guard against a stage that fails without changing status, which would
		// otherwise spin here forever. Say why: a silent exit here left episodes
		// reading as "preparing" with nothing in the log to explain it.
		d, _ := countDownloadable(ctx, r.pool)
		p, _ := countPending(ctx, r.pool)
		if d >= downloadable && p >= pending {
			log.Printf("ingest: stopping, no progress this cycle "+
				"(%d/%d before, %d/%d after) — see items.error for why",
				downloadable, pending, d, p)
			break
		}
	}

	log.Printf("ingest: run done in %s", time.Since(start).Round(time.Second))
}

// maxCycles bounds the alternation. One transcription per cycle, so reaching
// it means new work has been arriving for as long as the run has been going;
// the run ends and the watchdog picks up the rest.
const maxCycles = 200

// StartCron runs the pipeline daily at the configured local time. In-process
// rather than an external scheduler: fewer moving parts, per the spec.
func (r *Runner) StartCron(ctx context.Context) {
	go func() {
		for {
			next := nextRun(time.Now(), r.cfg.IngestHour, r.cfg.IngestMinute)
			wait := time.Until(next)
			log.Printf("ingest: next scheduled run %s (in %s)",
				next.Format(time.RFC3339), wait.Round(time.Minute))
			select {
			case <-ctx.Done():
				return
			case <-time.After(wait):
				r.Trigger("cron")
			}
		}
	}()
}

// MaxAttempts is how many times an item may fail before it is left alone.
// Bounded rather than infinite: an episode whose audio really is broken must
// not consume the queue on every run forever.
const MaxAttempts = 3

// ReclaimStuck resets in-progress and failed statuses to the stage that
// precedes them. All are safe to redo: download overwrites, and transcription
// replaces an item's segments wholesale.
func ReclaimStuck(ctx context.Context, conn *sql.DB) error {
	res, err := conn.ExecContext(ctx, `
		UPDATE items SET status = 'new'
		WHERE status = 'downloading'`)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()

	// An item mid-transcription with no file on disk cannot go back to
	// 'downloaded' — nothing would ever select it. Send it back to 'new' so
	// the download stage fetches the audio again.
	res2, err := conn.ExecContext(ctx, `
		UPDATE items SET status = CASE
		    WHEN audio_path IS NOT NULL THEN 'downloaded' ELSE 'new' END
		WHERE status = 'transcribing'`)
	if err != nil {
		return err
	}
	n2, _ := res2.RowsAffected()

	// A 'downloaded' row with no file is a contradiction: the transcriber will
	// never select it, but everything that counts pending work will keep
	// counting it, so the watchdog would retrigger forever. Send it back to
	// the download stage, which is the only thing that can resolve it.
	res4, err := conn.ExecContext(ctx, `
		UPDATE items SET status = 'new'
		WHERE status = 'downloaded' AND audio_path IS NULL`)
	if err != nil {
		return err
	}
	n4, _ := res4.RowsAffected()

	// Failures are retried, up to a point. A worker that was briefly out of
	// memory used to cost that episode permanently.
	res3, err := conn.ExecContext(ctx, `
		UPDATE items SET status = CASE
		    WHEN audio_path IS NOT NULL THEN 'downloaded' ELSE 'new' END,
		    attempts = attempts + 1
		WHERE status = 'failed' AND attempts < ?`, MaxAttempts)
	if err != nil {
		return err
	}
	n3, _ := res3.RowsAffected()

	if n+n2+n3+n4 > 0 {
		log.Printf("ingest: reclaimed %d stuck download(s), %d stuck transcription(s), "+
			"%d file-less item(s), %d failure(s) for retry", n, n2, n4, n3)
	}
	return nil
}

// StartWatchdog retriggers the pipeline whenever work is waiting and nothing
// is running.
//
// The nightly cron was the only thing that ever started a run, so any stall —
// a worker restart, a stage that exited early, a container bounce — parked
// every remaining episode until 03:30. The state machine is built to be
// resumed at any point; this is what actually resumes it.
func (r *Runner) StartWatchdog(ctx context.Context, every time.Duration) {
	go func() {
		ticker := time.NewTicker(every)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if r.Running() {
					continue
				}
				n, err := countWaiting(ctx, r.pool)
				if err != nil || n == 0 {
					continue
				}
				log.Printf("ingest: watchdog found %d item(s) waiting, starting a run", n)
				r.Trigger("watchdog")
			}
		}
	}()
}

// countWaiting is everything the pipeline still owes work on, at any stage.
func countWaiting(ctx context.Context, pool *sql.DB) (int, error) {
	var n int
	err := pool.QueryRowContext(ctx, `
		SELECT count(*) FROM items
		WHERE status IN ('new','downloading','downloaded','transcribing')
		   OR (status = 'failed' AND attempts < ?)`, MaxAttempts).Scan(&n)
	return n, err
}

// countPending reports how many items are waiting for transcription.
//
// The condition must match TranscribePending's exactly. It did not: this
// counted every 'downloaded' row while the transcriber only selected those
// with an audio path, so a row that had lost its file made the two disagree
// forever — the drain loop saw no progress, stopped, and the episodes read as
// "preparing" until something else happened to trigger a run.
func countPending(ctx context.Context, pool *sql.DB) (int, error) {
	var n int
	err := pool.QueryRowContext(ctx,
		`SELECT count(*) FROM items
		 WHERE status = 'downloaded' AND audio_path IS NOT NULL`).Scan(&n)
	return n, err
}

// countDownloadable must match DownloadPending's selection, for the same
// reason countPending must match the transcriber's: a count that disagrees
// with what the stage actually does turns a loop guard into a stall.
func countDownloadable(ctx context.Context, pool *sql.DB) (int, error) {
	var n int
	err := pool.QueryRowContext(ctx, `
		SELECT count(*) FROM items
		WHERE status = 'new' AND audio_url IS NOT NULL AND audio_url <> ''`).Scan(&n)
	return n, err
}

func nextRun(now time.Time, hour, min int) time.Time {
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, min, 0, 0, now.Location())
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}
