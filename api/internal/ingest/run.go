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
}

func NewRunner(pool *sql.DB, cfg config.Config) *Runner {
	return &Runner{pool: pool, cfg: cfg}
}

func (r *Runner) Running() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// Trigger starts a run in the background. Returns false if one is already going.
func (r *Runner) Trigger(reason string) bool {
	r.mu.Lock()
	if r.running {
		r.mu.Unlock()
		return false
	}
	r.running = true
	r.mu.Unlock()

	go func() {
		defer func() {
			r.mu.Lock()
			r.running = false
			r.mu.Unlock()
		}()
		// Detached from any request context: a manual trigger must not be
		// cancelled when the HTTP client hangs up.
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
		defer cancel()
		r.Run(ctx, reason)
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
	if err := DownloadPending(ctx, r.pool, r.cfg.AudioDir, 40); err != nil {
		log.Printf("ERROR ingest: download: %v", err)
	}
	// Drain the queue rather than doing a fixed five and stopping. A single
	// batch left items sitting in 'downloaded' with nothing scheduled to pick
	// them up until the next nightly run, so the app reported episodes as
	// preparing for hours while nothing was happening.
	for pass := 0; ; pass++ {
		remaining, err := countPending(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting pending: %v", err)
			break
		}
		if remaining == 0 {
			log.Printf("ingest: transcription queue empty")
			break
		}
		log.Printf("ingest: %d item(s) to transcribe (pass %d)", remaining, pass+1)

		if err := TranscribePending(ctx, r.pool, r.cfg.WorkerURL, r.cfg.RawDir, 5); err != nil {
			log.Printf("ERROR ingest: transcribe: %v", err)
			break
		}
		// Guard against a stage that fails without changing status, which
		// would otherwise spin here forever. Say why: a silent exit here left
		// episodes reading as "preparing" with nothing in the log to explain
		// it, and the watchdog below then retried it every five minutes.
		after, err := countPending(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting pending: %v", err)
			break
		}
		if after >= remaining {
			log.Printf("ingest: stopping, no progress this pass (%d before, %d after) — "+
				"see items.error for why", remaining, after)
			break
		}
		if ctx.Err() != nil {
			log.Printf("ingest: stopping, context ended: %v", ctx.Err())
			break
		}
	}

	log.Printf("ingest: run done in %s", time.Since(start).Round(time.Second))
}

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

func nextRun(now time.Time, hour, min int) time.Time {
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, min, 0, 0, now.Location())
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}
