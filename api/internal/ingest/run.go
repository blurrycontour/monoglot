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

	// Wakes the cron loop when the schedule is edited. Buffered and sent to
	// without blocking: the loop only needs to know that something changed,
	// and it re-reads the table itself.
	reload chan struct{}
}

func NewRunner(pool *sql.DB, cfg config.Config) *Runner {
	return &Runner{pool: pool, cfg: cfg, reload: make(chan struct{}, 1)}
}

// ReloadSchedules tells the cron loop to recompute its next wake-up. Called
// after the schedule is edited, so a time added for eight minutes from now
// fires then rather than after the loop's existing sleep expires.
func (r *Runner) ReloadSchedules() {
	select {
	case r.reload <- struct{}{}:
	default:
	}
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

	// Downloads run alongside transcription rather than taking turns with it.
	//
	// They used to alternate: download everything, transcribe one, download
	// again. Anything queued while a transcription was running therefore sat
	// at "waiting to download" for the whole of it — five minutes of CPU
	// blocking two seconds of I/O — and only moved when the current episode
	// finished or was cancelled, which is exactly what it looked like.
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		r.downloadLoop(ctx, stop)
	}()

	r.transcribeLoop(ctx)
	close(stop)
	wg.Wait()

	log.Printf("ingest: run done in %s", time.Since(start).Round(time.Second))
}

// maxJobs bounds a single run's transcriptions. Reaching it means work has
// been arriving for as long as the run has been going; the run ends and the
// watchdog picks up the rest rather than one run continuing forever.
const maxJobs = 200

// idlePoll is how often each loop re-checks for work the other one produced.
// Both queries are indexed counts against a local file; this is cheap.
const idlePoll = time.Second

// maxIdle is how long the transcriber waits on a downloader that is producing
// nothing before giving up on the run.
const maxIdle = 5 * time.Minute

// downloadLoop fetches audio for as long as the run lasts. Serial within
// itself — one file at a time, so the progress the app shows has one subject —
// but concurrent with transcription, which is someone else's CPU.
func (r *Runner) downloadLoop(ctx context.Context, stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		case <-ctx.Done():
			return
		default:
		}

		n, err := countDownloadable(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting downloadable: %v", err)
			return
		}
		if n == 0 {
			select {
			case <-stop:
				return
			case <-ctx.Done():
				return
			case <-time.After(idlePoll):
			}
			continue
		}

		done, err := DownloadPending(ctx, r.pool, r.cfg.AudioDir, 40)
		if err != nil {
			log.Printf("ERROR ingest: download: %v", err)
			return
		}
		if done == 0 {
			// Selectable but not selected: the two conditions disagree, which
			// is the shape of every stall this pipeline has had. Stop rather
			// than spin, and say so.
			log.Printf("ingest: download stage selected nothing while %d item(s) "+
				"looked downloadable — stopping", n)
			return
		}
	}
}

// transcribeLoop works the transcription queue until there is nothing left to
// transcribe and nothing on its way. It owns when the run ends.
func (r *Runner) transcribeLoop(ctx context.Context) {
	idleSince := time.Time{}
	for jobs := 0; jobs < maxJobs; {
		pending, err := countPending(ctx, r.pool)
		if err != nil {
			log.Printf("ERROR ingest: counting pending: %v", err)
			return
		}

		if pending == 0 {
			// Nothing to transcribe now — but the downloader may be about to
			// produce something, so ending the run here would strand it.
			waiting, err := countIncoming(ctx, r.pool)
			if err != nil || waiting == 0 {
				log.Printf("ingest: queue empty")
				return
			}
			if idleSince.IsZero() {
				idleSince = time.Now()
			} else if time.Since(idleSince) > maxIdle {
				log.Printf("ingest: %d item(s) still not downloaded after %s, ending run",
					waiting, maxIdle)
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(idlePoll):
			}
			continue
		}
		idleSince = time.Time{}

		log.Printf("ingest: %d item(s) to transcribe", pending)
		// One at a time, re-selected each pass: a batch picked up front goes
		// stale the moment anything is cancelled.
		done, err := TranscribePending(ctx, r.pool, r.cfg.WorkerURL, r.cfg.RawDir, 1)
		if err != nil {
			log.Printf("ERROR ingest: transcribe: %v", err)
			return
		}
		if done == 0 {
			// Counted as pending but not selected. Same disagreement as above,
			// and the reason the app used to say "preparing" indefinitely.
			log.Printf("ingest: %d item(s) pending but none selectable — stopping, "+
				"see items.error for why", pending)
			return
		}
		jobs += done
		if ctx.Err() != nil {
			log.Printf("ingest: stopping, context ended: %v", ctx.Err())
			return
		}
	}
	log.Printf("ingest: reached the %d job limit for one run; the watchdog will continue", maxJobs)
}

// StartCron runs the pipeline at each configured local time. In-process
// rather than an external scheduler: fewer moving parts, per the spec.
//
// The times live in the database and are edited from the app, so the loop
// re-reads them on every pass and can be woken between passes. A server with
// no schedule sleeps on a nil channel until one is added — it does not poll,
// and it does not quietly ingest on a timetable nobody chose.
func (r *Runner) StartCron(ctx context.Context) {
	go func() {
		for {
			var wake <-chan time.Time
			var timer *time.Timer
			if next, ok := r.NextRun(ctx, time.Now()); ok {
				wait := time.Until(next)
				log.Printf("ingest: next scheduled run %s (in %s)",
					next.Format(time.RFC3339), wait.Round(time.Minute))
				// Stopped on every path out of the select rather than
				// deferred: a deferred stop in this loop would hold every
				// timer the process ever armed until the loop itself ended.
				timer = time.NewTimer(wait)
				wake = timer.C
			} else {
				log.Printf("ingest: no schedule set, nothing will run unattended")
			}
			select {
			case <-ctx.Done():
				stop(timer)
				return
			case <-r.reload:
				stop(timer)
			case <-wake:
				r.Trigger("cron")
			}
		}
	}()
}

func stop(t *time.Timer) {
	if t != nil {
		t.Stop()
	}
}

// NextRun is the soonest configured time strictly after now, if there is one.
func (r *Runner) NextRun(ctx context.Context, now time.Time) (time.Time, bool) {
	times, err := ListSchedules(ctx, r.pool)
	if err != nil {
		log.Printf("ingest: reading schedules: %v", err)
		return time.Time{}, false
	}
	var best time.Time
	for _, t := range times {
		at := nextRun(now, t.Hour, t.Minute)
		if best.IsZero() || at.Before(best) {
			best = at
		}
	}
	return best, !best.IsZero()
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

// countIncoming is work the download stage still owes the transcriber: items
// waiting to be fetched, and the one being fetched right now.
func countIncoming(ctx context.Context, pool *sql.DB) (int, error) {
	var n int
	err := pool.QueryRowContext(ctx, `
		SELECT count(*) FROM items
		WHERE status = 'downloading'
		   OR (status = 'new' AND audio_url IS NOT NULL AND audio_url <> '')`).Scan(&n)
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
