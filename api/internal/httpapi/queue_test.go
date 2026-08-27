package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/config"
	"github.com/blurrycontour/monoglot/api/internal/db"
	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

// The queue is a state machine with two slow stages and a user who can pull an
// item out of either of them. Every one of the bugs this file guards against
// was a disagreement between two parts of it — a count that did not match what
// a stage selected, a cancellation a stage did not notice, a status nothing
// would ever move on. They are cheap to test and expensive to find in
// production, where the symptom is always the same: episodes that say
// "preparing" forever.

// fakeWorker stands in for the Python service. Transcription takes a settable
// number of 20ms ticks and, like the real one, checks between them whether the
// path has been cancelled.
type fakeWorker struct {
	mu        sync.Mutex
	cancelled map[string]bool
	running   map[string]bool
	started   map[string]int
	ticks     int
	fail      bool // return 500 instead of a transcript
	empty     bool // return 200 with no segments

	audioDelay time.Duration // how long each audio body takes to serve
	dlNow      int           // downloads in flight
	dlPeak     int           // the most that were ever in flight at once

	rejectModel string   // a model id /validate refuses
	models      []string // model ids seen on /transcribe, in order
}

func newFakeWorker() *fakeWorker {
	return &fakeWorker{
		cancelled: map[string]bool{},
		running:   map[string]bool{},
		started:   map[string]int{},
		ticks:     3,
	}
}

func (f *fakeWorker) handler() http.Handler {
	mux := http.NewServeMux()

	// Audio, for the download stage.
	mux.HandleFunc("/audio/", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.dlNow++
		if f.dlNow > f.dlPeak {
			f.dlPeak = f.dlNow
		}
		delay := f.audioDelay
		f.mu.Unlock()
		defer func() {
			f.mu.Lock()
			f.dlNow--
			f.mu.Unlock()
		}()

		w.Header().Set("Content-Length", "8")
		if delay > 0 {
			// Half the body, a pause, then the rest: enough for the run to
			// observe a download that is genuinely in flight.
			w.Write([]byte("0123"))
			if fl, ok := w.(http.Flusher); ok {
				fl.Flush()
			}
			select {
			case <-r.Context().Done():
				return
			case <-time.After(delay):
			}
			w.Write([]byte("4567"))
			return
		}
		w.Write([]byte("01234567"))
	})

	mux.HandleFunc("/cancel", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			AudioPath string `json:"audio_path"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		f.mu.Lock()
		f.cancelled[req.AudioPath] = true
		running := f.running[req.AudioPath]
		f.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{"status": "ok", "running": running})
	})

	mux.HandleFunc("/validate", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Model string `json:"model"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		f.mu.Lock()
		reject := f.rejectModel != "" && f.rejectModel == req.Model
		f.mu.Unlock()
		if reject {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"detail":"no CTranslate2 model.bin"}`))
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "model": req.Model})
	})

	mux.HandleFunc("/transcribe", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			AudioPath string `json:"audio_path"`
			Language  string `json:"language"`
			Model     string `json:"model"`
		}
		json.NewDecoder(r.Body).Decode(&req)

		f.mu.Lock()
		f.models = append(f.models, req.Model)
		f.running[req.AudioPath] = true
		f.started[req.AudioPath]++
		delete(f.cancelled, req.AudioPath)
		ticks := f.ticks
		fail, empty := f.fail, f.empty
		f.mu.Unlock()
		defer func() {
			f.mu.Lock()
			delete(f.running, req.AudioPath)
			f.mu.Unlock()
		}()

		for i := 0; i < ticks; i++ {
			select {
			case <-r.Context().Done():
				return
			case <-time.After(20 * time.Millisecond):
			}
			f.mu.Lock()
			stop := f.cancelled[req.AudioPath]
			f.mu.Unlock()
			if stop {
				http.Error(w, "cancelled", http.StatusConflict)
				return
			}
		}

		if fail {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		out := map[string]any{"language": "sv", "duration": 1.0, "model": "fake"}
		if !empty {
			out["segments"] = []map[string]any{{
				"start": 0.0, "end": 1.0, "text": "hej du",
				"words": []map[string]any{
					{"word": "hej", "start": 0.0, "end": 0.4},
					{"word": "du", "start": 0.5, "end": 1.0},
				},
			}}
		}
		json.NewEncoder(w).Encode(out)
	})
	return mux
}

func (f *fakeWorker) startCount(path string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.started[path]
}

func (f *fakeWorker) peakDownloads() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dlPeak
}

// rig is a whole instance: schema, config, runner, HTTP surface and a worker.
type rig struct {
	t      *testing.T
	pool   *sql.DB
	srv    *Server
	api    *httptest.Server
	worker *fakeWorker
	source int
}

func newRig(t *testing.T) *rig {
	t.Helper()
	dir := t.TempDir()
	ctx := context.Background()

	pool, err := db.Connect(ctx, filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(func() { pool.Close() })
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	// The seed migration ships four real sources, enabled. Left alone, every
	// test in this file would fetch Sveriges Radio over the network — slow,
	// flaky, and rude. The pipeline is exercised with fixtures instead.
	if _, err := pool.ExecContext(ctx, `UPDATE sources SET enabled = 0`); err != nil {
		t.Fatalf("disabling seeded sources: %v", err)
	}

	worker := newFakeWorker()
	ws := httptest.NewServer(worker.handler())
	t.Cleanup(ws.Close)

	// Disabled so Run's discovery step does not reach the network, with a
	// window wide enough that DeferOutOfWindow leaves the fixtures alone.
	res, err := pool.ExecContext(ctx, `
		INSERT INTO sources (slug, name, kind, config, enabled, auto_download_limit)
		VALUES ('test', 'Test', 'rss', '{}', 0, 500)`)
	if err != nil {
		t.Fatalf("source: %v", err)
	}
	sid, _ := res.LastInsertId()

	cfg := config.Config{
		AudioDir:  filepath.Join(dir, "audio"),
		RawDir:    filepath.Join(dir, "raw"),
		WorkerURL: ws.URL,
		AuthToken: "test-token",
	}
	runner := ingest.NewRunner(pool, cfg)
	srv := NewServer(pool, cfg, runner)
	api := httptest.NewServer(srv.Routes())
	t.Cleanup(api.Close)

	return &rig{t: t, pool: pool, srv: srv, api: api, worker: worker, source: int(sid)}
}

// addItem inserts one episode at a given stage. Audio is served by the same
// test server as the worker, so the download stage is exercised for real.
func (r *rig) addItem(status string) int {
	r.t.Helper()
	var path any
	if status == "downloaded" || status == "transcribing" {
		p := filepath.Join(r.srv.cfg.AudioDir, "x.mp3")
		path = p
	}
	res, err := r.pool.Exec(`
		INSERT INTO items (source_id, external_id, title, published_at, audio_url, status)
		VALUES (?, ?, ?, ?, ?, ?)`,
		r.source, fmt.Sprintf("e%d", time.Now().UnixNano()), "Episode",
		db.FormatTime(time.Now()), r.worker0AudioURL(), status)
	if err != nil {
		r.t.Fatalf("insert: %v", err)
	}
	id64, _ := res.LastInsertId()
	id := int(id64)
	if path != nil {
		// A real 'downloaded' row has a file; several reclaim rules turn on it.
		p := filepath.Join(r.srv.cfg.AudioDir, fmt.Sprintf("%d.mp3", id))
		writeFile(r.t, p)
		r.pool.Exec(`UPDATE items SET audio_path=? WHERE id=?`, p, id)
	}
	return id
}

func (r *rig) worker0AudioURL() string {
	return r.srv.cfg.WorkerURL + "/audio/x.mp3"
}

func (r *rig) status(id int) string {
	r.t.Helper()
	var s string
	if err := r.pool.QueryRow(`SELECT status FROM items WHERE id=?`, id).Scan(&s); err != nil {
		r.t.Fatalf("status %d: %v", id, err)
	}
	return s
}

func (r *rig) attempts(id int) int {
	r.t.Helper()
	var n int
	r.pool.QueryRow(`SELECT attempts FROM items WHERE id=?`, id).Scan(&n)
	return n
}

func (r *rig) audioPath(id int) string {
	r.t.Helper()
	var p sql.NullString
	r.pool.QueryRow(`SELECT audio_path FROM items WHERE id=?`, id).Scan(&p)
	return p.String
}

// post calls the real HTTP surface, auth and routing included.
func (r *rig) post(path string) *http.Response {
	r.t.Helper()
	req, _ := http.NewRequest(http.MethodPost, r.api.URL+path, nil)
	req.Header.Set("Authorization", "Bearer test-token")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		r.t.Fatalf("POST %s: %v", path, err)
	}
	io.Copy(io.Discard, res.Body)
	res.Body.Close()
	return res
}

func (r *rig) get(path string, into any) {
	r.t.Helper()
	req, _ := http.NewRequest(http.MethodGet, r.api.URL+path, nil)
	req.Header.Set("Authorization", "Bearer test-token")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		r.t.Fatalf("GET %s: %v", path, err)
	}
	defer res.Body.Close()
	if err := json.NewDecoder(res.Body).Decode(into); err != nil {
		r.t.Fatalf("GET %s: decode: %v", path, err)
	}
}

func writeFile(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte("audio"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
}

// waitFor polls a condition. Every wait here is on work that takes
// milliseconds in this harness; a second is generous.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func runInBackground(r *rig) {
	go r.srv.runner.Run(context.Background(), "test")
}

// --- the cases -------------------------------------------------------------

// Cancelling the item being transcribed must free the worker, not wait for it,
// and must not look like a failure afterwards.
func TestCancelWhileTranscribing(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.ticks = 200 // ~4s: long enough that finishing would be obvious
	r.worker.mu.Unlock()

	id := r.addItem("downloaded")
	other := r.addItem("new")
	runInBackground(r)

	waitFor(t, "transcription to start", func() bool { return r.status(id) == "transcribing" })

	start := time.Now()
	res := r.post(fmt.Sprintf("/api/items/%d/cancel", id))
	if res.StatusCode != http.StatusOK {
		t.Fatalf("cancel: status %d", res.StatusCode)
	}

	waitFor(t, "the cancelled item to leave the pipeline", func() bool {
		return r.status(id) == "archived"
	})
	if took := time.Since(start); took > 2*time.Second {
		t.Errorf("cancel took %s; it should not wait for the worker", took)
	}
	if got := r.attempts(id); got != 0 {
		t.Errorf("attempts = %d, want 0: cancelling is not a failure", got)
	}
	if p := r.audioPath(id); p != "" {
		t.Errorf("audio_path = %q, want empty: the audio is deleted on cancel", p)
	}

	// And the rest of the queue keeps moving rather than waiting it out.
	waitFor(t, "the next item to be picked up", func() bool {
		return r.status(other) != "new"
	})
}

// Cancelling something merely queued must take it out before any stage
// selects it — the bug this replaces sent a deleted file to the worker.
func TestCancelWhileQueued(t *testing.T) {
	r := newRig(t)
	id := r.addItem("downloaded")
	path := r.audioPath(id)

	r.post(fmt.Sprintf("/api/items/%d/cancel", id))
	if got := r.status(id); got != "archived" {
		t.Fatalf("status = %q, want archived", got)
	}

	r.srv.runner.Run(context.Background(), "test")
	if n := r.worker.startCount(path); n != 0 {
		t.Errorf("worker was called %d time(s) for a cancelled item", n)
	}
	if got := r.status(id); got != "archived" {
		t.Errorf("status = %q, want it to stay archived", got)
	}
}

// A cancelled item is re-fetchable: that is where the app sends the user.
func TestCancelThenRestore(t *testing.T) {
	r := newRig(t)
	id := r.addItem("new")
	r.post(fmt.Sprintf("/api/items/%d/cancel", id))
	if got := r.status(id); got != "archived" {
		t.Fatalf("status = %q, want archived", got)
	}
	if res := r.post(fmt.Sprintf("/api/items/%d/restore", id)); res.StatusCode != http.StatusOK {
		t.Fatalf("restore: status %d", res.StatusCode)
	}
	// No explicit run: restoring triggers one, and cancelling already did.
	// Asserting 'new' here would be a race against exactly the promptness
	// this is meant to check.
	waitFor(t, "the restored item to be processed", func() bool {
		return r.status(id) == "ready"
	})
}

// Cancelling twice is something a slow network makes easy to do by accident.
func TestCancelIsIdempotent(t *testing.T) {
	r := newRig(t)
	id := r.addItem("new")
	if res := r.post(fmt.Sprintf("/api/items/%d/cancel", id)); res.StatusCode != http.StatusOK {
		t.Fatalf("first cancel: %d", res.StatusCode)
	}
	// The second is refused rather than silently re-archiving: an archived
	// item is not in the pipeline to be taken out of.
	if res := r.post(fmt.Sprintf("/api/items/%d/cancel", id)); res.StatusCode != http.StatusBadRequest {
		t.Fatalf("second cancel: status %d, want 400", res.StatusCode)
	}
	if got := r.status(id); got != "archived" {
		t.Errorf("status = %q, want archived", got)
	}
}

// Queueing something mid-run must be picked up by that run. It used to wait
// for the whole transcription queue and then for the watchdog.
func TestItemQueuedMidRunIsPickedUp(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.ticks = 25 // ~500ms per item
	r.worker.mu.Unlock()

	first := r.addItem("downloaded")
	runInBackground(r)
	waitFor(t, "the run to reach transcription", func() bool {
		return r.status(first) == "transcribing"
	})

	late := r.addItem("new")
	waitFor(t, "the late arrival to be downloaded", func() bool {
		return r.status(late) == "downloaded" || r.status(late) == "transcribing" ||
			r.status(late) == "ready"
	})
	waitFor(t, "both to finish", func() bool {
		return r.status(first) == "ready" && r.status(late) == "ready"
	})
}

// Downloading must not wait for the CPU-bound stage. This is what "waiting to
// download" for five minutes looked like, and what made cancelling the current
// episode appear to release five others at once.
func TestDownloadsRunDuringTranscription(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.ticks = 150 // ~3s, long enough to observe the overlap
	r.worker.mu.Unlock()

	busy := r.addItem("downloaded")
	runInBackground(r)
	waitFor(t, "transcription to start", func() bool { return r.status(busy) == "transcribing" })

	late := r.addItem("new")
	waitFor(t, "the late arrival to be downloaded", func() bool {
		return r.status(late) == "downloaded"
	})
	// The point of the test: this happened while the CPU stage was still busy.
	if got := r.status(busy); got != "transcribing" {
		t.Fatalf("the transcription finished first (%q); the overlap was not observed", got)
	}
}

// A trigger that arrives during a run is remembered rather than dropped.
func TestTriggerDuringRunReruns(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.ticks = 25
	r.worker.mu.Unlock()

	first := r.addItem("downloaded")
	if !r.srv.runner.Trigger("first") {
		t.Fatal("first trigger refused")
	}
	waitFor(t, "the run to start", func() bool { return r.srv.runner.Running() })

	late := r.addItem("new")
	if r.srv.runner.Trigger("second") {
		t.Fatal("second trigger started a concurrent run; runs must stay serial")
	}
	waitFor(t, "the remembered trigger to do the late item", func() bool {
		return r.status(late) == "ready"
	})
	if got := r.status(first); got != "ready" {
		t.Errorf("first = %q, want ready", got)
	}
}

// A worker failure is retried, but only up to MaxAttempts.
func TestFailureIsRetriedThenLeftAlone(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.fail = true
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	id := r.addItem("downloaded")
	for i := 0; i < ingest.MaxAttempts+2; i++ {
		r.srv.runner.Run(context.Background(), "test")
	}
	if got := r.status(id); got != "failed" {
		t.Fatalf("status = %q, want failed", got)
	}
	if got := r.attempts(id); got != ingest.MaxAttempts {
		t.Errorf("attempts = %d, want %d — a broken episode must not be retried forever",
			got, ingest.MaxAttempts)
	}
}

// An empty transcript is a failure, not a ready item with no words in it.
func TestEmptyTranscriptFails(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.empty = true
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	id := r.addItem("downloaded")
	r.srv.runner.Run(context.Background(), "test")
	if got := r.status(id); got == "ready" {
		t.Fatalf("status = ready; an item with no segments is not playable")
	}
}

// A 'downloaded' row with no file is the contradiction that stalled prod: the
// transcriber will not select it, but everything that counts pending work
// does. It must be sent back to the stage that can resolve it.
func TestFilelessDownloadedRowIsReclaimed(t *testing.T) {
	r := newRig(t)
	id := r.addItem("downloaded")
	r.pool.Exec(`UPDATE items SET audio_path=NULL WHERE id=?`, id)

	if err := ingest.ReclaimStuck(context.Background(), r.pool); err != nil {
		t.Fatalf("reclaim: %v", err)
	}
	if got := r.status(id); got != "new" {
		t.Fatalf("status = %q, want new", got)
	}
	r.srv.runner.Run(context.Background(), "test")
	if got := r.status(id); got != "ready" {
		t.Errorf("status = %q, want ready: the item must recover on its own", got)
	}
}

// Interrupted stages are resumable. Nothing may be left in a state no stage
// selects.
func TestReclaimLeavesNothingStranded(t *testing.T) {
	r := newRig(t)
	downloading := r.addItem("new")
	r.pool.Exec(`UPDATE items SET status='downloading' WHERE id=?`, downloading)
	transcribing := r.addItem("transcribing")

	if err := ingest.ReclaimStuck(context.Background(), r.pool); err != nil {
		t.Fatalf("reclaim: %v", err)
	}
	if got := r.status(downloading); got != "new" {
		t.Errorf("interrupted download = %q, want new", got)
	}
	if got := r.status(transcribing); got != "downloaded" {
		t.Errorf("interrupted transcription = %q, want downloaded", got)
	}

	r.srv.runner.Run(context.Background(), "test")
	for _, id := range []int{downloading, transcribing} {
		if got := r.status(id); got != "ready" {
			t.Errorf("item %d = %q, want ready", id, got)
		}
	}
}

// The counters the app reads must agree with what the stages select. When they
// disagreed, the run's no-progress guard fired and everything stalled.
func TestStatusCountsMatchTheQueue(t *testing.T) {
	r := newRig(t)
	r.addItem("new")
	r.addItem("downloaded")
	arch := r.addItem("new")
	r.post(fmt.Sprintf("/api/items/%d/cancel", arch))

	var st struct {
		Processing int `json:"processing"`
		Archived   int `json:"archived"`
		Items      []struct {
			ID     int    `json:"id"`
			Status string `json:"status"`
		} `json:"items"`
	}
	r.get("/api/status", &st)
	if st.Processing != 2 {
		t.Errorf("processing = %d, want 2", st.Processing)
	}
	if st.Archived != 1 {
		t.Errorf("archived = %d, want 1 — deferred work is not in flight", st.Archived)
	}
	if len(st.Items) != 2 {
		t.Fatalf("items = %d, want 2 (the archived one is not queued)", len(st.Items))
	}
	for _, it := range st.Items {
		if it.Status == "archived" {
			t.Errorf("item %d is archived but listed as queued", it.ID)
		}
	}

	// Cancelling already triggered a run, so waiting is the honest check —
	// calling Run again here would race it and prove nothing.
	waitFor(t, "the queue to drain", func() bool {
		r.get("/api/status", &st)
		return st.Processing == 0
	})
}

// A run must end. The guard that stops it must not stop it early either: a
// queue that is being worked through has to drain in one run.
func TestRunDrainsTheWholeQueue(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	var ids []int
	for i := 0; i < 7; i++ {
		ids = append(ids, r.addItem("new"))
	}
	done := make(chan struct{})
	go func() { r.srv.runner.Run(context.Background(), "test"); close(done) }()
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		t.Fatal("run did not finish")
	}
	for _, id := range ids {
		if got := r.status(id); got != "ready" {
			t.Errorf("item %d = %q, want ready", id, got)
		}
	}
}

// Downloads run a few at a time, so the queue shows a handful of moving bars
// rather than one — and not all of them at once, which would show nothing.
func TestDownloadsRunAFewAtATime(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.audioDelay = 300 * time.Millisecond
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	for i := 0; i < 8; i++ {
		r.addItem("new")
	}
	runInBackground(r)

	// While they are being fetched, exactly the configured number should be
	// in flight and the rest should still be waiting their turn.
	waitFor(t, "downloads to be in flight", func() bool {
		return r.worker.peakDownloads() > 1
	})
	waitFor(t, "the queue to drain", func() bool {
		var n int
		r.pool.QueryRow(`SELECT count(*) FROM items WHERE status='ready'`).Scan(&n)
		return n == 8
	})

	peak := r.worker.peakDownloads()
	if peak > ingest.MaxConcurrentDownloads {
		t.Errorf("%d downloads at once, limit is %d", peak, ingest.MaxConcurrentDownloads)
	}
	if peak < 2 {
		t.Errorf("peak concurrency %d: downloads are still serial", peak)
	}
}

// Every download in flight reports its own progress. One shared slot meant
// only whichever started last had a bar.
func TestEachDownloadReportsItsOwnProgress(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.audioDelay = 1500 * time.Millisecond
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	for i := 0; i < 3; i++ {
		r.addItem("new")
	}
	runInBackground(r)

	var st struct {
		Items []struct {
			ID        int     `json:"id"`
			Status    string  `json:"status"`
			BytesDone int64   `json:"bytes_done"`
			Progress  float64 `json:"progress"`
		} `json:"items"`
	}
	waitFor(t, "several downloads to report bytes", func() bool {
		r.get("/api/status", &st)
		n := 0
		for _, it := range st.Items {
			if it.Status == "downloading" && it.BytesDone > 0 {
				n++
			}
		}
		return n >= 2
	})
}

// Cancelling something mid-download must stick. The download finishing
// afterwards must not write the row back to 'downloaded'.
func TestCancelWhileDownloading(t *testing.T) {
	r := newRig(t)
	r.worker.mu.Lock()
	r.worker.audioDelay = time.Second
	r.worker.ticks = 1
	r.worker.mu.Unlock()

	id := r.addItem("new")
	runInBackground(r)
	waitFor(t, "the download to start", func() bool { return r.status(id) == "downloading" })

	if res := r.post(fmt.Sprintf("/api/items/%d/cancel", id)); res.StatusCode != http.StatusOK {
		t.Fatalf("cancel: status %d", res.StatusCode)
	}
	// Long enough for the download to have finished had it been left alone.
	time.Sleep(1500 * time.Millisecond)
	if got := r.status(id); got != "archived" {
		t.Fatalf("status = %q, want archived: a finishing download resurrected it", got)
	}
	if p := r.audioPath(id); p != "" {
		t.Errorf("audio_path = %q, want empty", p)
	}
}
