package ingest

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adityasingh/svenska/api/internal/config"
)

// Runner serialises pipeline execution. Only one run may be in flight at a
// time: transcription is CPU-bound and overlapping runs would thrash.
type Runner struct {
	pool *pgxpool.Pool
	cfg  config.Config

	mu      sync.Mutex
	running bool
}

func NewRunner(pool *pgxpool.Pool, cfg config.Config) *Runner {
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
			break
		}
		if pass > 0 {
			log.Printf("ingest: %d item(s) still to transcribe", remaining)
		}
		if err := TranscribePending(ctx, r.pool, r.cfg.WorkerURL, r.cfg.RawDir, 5); err != nil {
			log.Printf("ERROR ingest: transcribe: %v", err)
			break
		}
		// Guard against a stage that fails without changing status, which
		// would otherwise spin here forever.
		after, err := countPending(ctx, r.pool)
		if err != nil || after >= remaining {
			break
		}
		if ctx.Err() != nil {
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

// countPending reports how many items are waiting for transcription.
func countPending(ctx context.Context, pool *pgxpool.Pool) (int, error) {
	var n int
	err := pool.QueryRow(ctx,
		`SELECT count(*) FROM items WHERE status = 'downloaded'`).Scan(&n)
	return n, err
}

func nextRun(now time.Time, hour, min int) time.Time {
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, min, 0, 0, now.Location())
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}
