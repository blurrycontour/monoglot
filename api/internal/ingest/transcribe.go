package ingest

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"

	"database/sql"

	"github.com/blurrycontour/monoglot/api/internal/lexicon"
)

// Word and Segment mirror the worker's JSON contract. The worker speaks in
// float seconds (that is what faster-whisper produces); we convert to integer
// milliseconds at this boundary and never store a float.
type Word struct {
	Word  string  `json:"word"`
	Start float64 `json:"start"`
	End   float64 `json:"end"`
}

type Segment struct {
	Start float64 `json:"start"`
	End   float64 `json:"end"`
	Text  string  `json:"text"`
	Words []Word  `json:"words"`
}

type TranscriptResponse struct {
	Language string    `json:"language"`
	Duration float64   `json:"duration"`
	Model    string    `json:"model"`
	Segments []Segment `json:"segments"`
}

// TranscribePending drives status='downloaded' -> 'ready'.
//
// Idempotent by construction: segments/tokens for the item are deleted before
// re-insert, and an item already at 'ready' is never picked up, so re-running
// the pipeline does not re-transcribe finished work.
// Returns how many jobs it took on. Zero means nothing was selectable, which
// is the only reliable "no work" signal now that downloads run alongside this:
// comparing queue lengths before and after cannot tell a transcription that
// finished from a download that arrived while it ran.
func TranscribePending(ctx context.Context, pool *sql.DB, workerURL, rawDir string, limit int) (int, error) {
	rows, err := pool.QueryContext(ctx, `
		SELECT id, audio_path FROM items
		WHERE status = 'downloaded' AND audio_path IS NOT NULL
		ORDER BY published_at DESC NULLS LAST
		LIMIT ?`, limit)
	if err != nil {
		return 0, err
	}
	type job struct {
		id   int
		path string
	}
	var jobs []job
	for rows.Next() {
		var j job
		if err := rows.Scan(&j.id, &j.path); err != nil {
			rows.Close()
			return 0, err
		}
		jobs = append(jobs, j)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	for _, j := range jobs {
		start := time.Now()
		if err := transcribeItem(ctx, pool, workerURL, rawDir, j.id, j.path); err != nil {
			if errors.Is(err, errSkip) {
				log.Printf("transcribe item %d: skipped, no longer pending", j.id)
				continue
			}
			log.Printf("ERROR transcribe item %d: %v", j.id, err)
			markFailed(ctx, pool, j.id, err)
			continue
		}
		log.Printf("transcribe item %d: ready in %s", j.id, time.Since(start).Round(time.Second))
	}
	return len(jobs), nil
}

// errSkip means the item is no longer ours to work on. Not a failure: the row
// changed under us, which is exactly what cancelling does.
var errSkip = errors.New("no longer pending")

// Worker calls currently in flight, so a cancellation can stop waiting on one.
// The worker itself cannot be interrupted — it is mid-inference and will run
// to completion — but nothing here needs to wait for a result it will discard.
var (
	inFlightMu sync.Mutex
	inFlight   = map[int]context.CancelFunc{}
)

func registerTranscription(id int, abort context.CancelFunc) {
	inFlightMu.Lock()
	inFlight[id] = abort
	inFlightMu.Unlock()
}

func unregisterTranscription(id int) {
	inFlightMu.Lock()
	delete(inFlight, id)
	inFlightMu.Unlock()
}

// errCancelled is the worker saying it abandoned the job because we asked it
// to. Distinct from a failure: nothing is wrong with the episode.
var errCancelled = errors.New("cancelled by request")

// CancelWorker asks the worker to stop transcribing a path. The worker checks
// between segments, so it gives up the CPU within a second or two instead of
// running the full episode out and having its result discarded.
//
// Best-effort by design: an unreachable or older worker simply means falling
// back to abandoning the HTTP call, which is what used to happen every time.
func CancelWorker(ctx context.Context, workerURL, audioPath string) error {
	body, _ := json.Marshal(map[string]string{"audio_path": audioPath})
	reqCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodPost,
		strings.TrimRight(workerURL, "/")+"/cancel", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("worker cancel: status %d", res.StatusCode)
	}
	return nil
}

// AbortTranscription stops waiting on the worker call for an item, if one is
// in flight. Reports whether there was one.
func AbortTranscription(id int) bool {
	inFlightMu.Lock()
	abort, ok := inFlight[id]
	delete(inFlight, id)
	inFlightMu.Unlock()
	if ok {
		abort()
	}
	return ok
}

func transcribeItem(ctx context.Context, pool *sql.DB, workerURL, rawDir string, id int, audioPath string) error {
	// Re-read the row rather than trusting the batch. TranscribePending picks
	// five jobs up front and then works through them one at a time, so by the
	// time a job starts it may have been cancelled minutes ago — and cancelling
	// deletes the audio, which turned into "no such file" from the worker and
	// a 'failed' row that the retry logic then resurrected.
	var status string
	var path sql.NullString
	if err := pool.QueryRowContext(ctx,
		`SELECT status, audio_path FROM items WHERE id=?`, id).Scan(&status, &path); err != nil {
		return err
	}
	if status != "downloaded" || !path.Valid || path.String == "" {
		return errSkip
	}
	audioPath = path.String

	// The item's language drives both the ASR hint and which dictionary the
	// lemmatiser consults.
	lang := itemLanguage(ctx, pool, id)
	asr := lexicon.ASRCode(ctx, pool, id)
	if _, err := pool.ExecContext(ctx,
		`UPDATE items SET status='transcribing', error=NULL WHERE id=?`, id); err != nil {
		return err
	}

	// The worker call is registered so that cancelling this item can abort it.
	// Without that, cancelling an episode mid-transcription left the pipeline
	// parked on a request whose result was going to be thrown away, and every
	// other queued episode waited behind it.
	callCtx, abort := context.WithCancel(ctx)
	// Deferred, not called here: calling it before the check below would make
	// callCtx.Err() non-nil for every outcome, so a worker failure would read
	// as a cancellation and never be marked failed or retried.
	defer abort()
	registerTranscription(id, abort)
	// Read here rather than passed down from the run: the model is edited from
	// the app, and a batch that started before the change should pick it up on
	// its next item, not on the next restart.
	model := TranscriptionModel(ctx, pool)
	resp, err := callWorker(callCtx, workerURL, audioPath, asr, model)
	unregisterTranscription(id)
	if err != nil {
		// The worker gave the job up because we asked, or we stopped waiting
		// for it. Either way the user cancelled: not a failure, and
		// specifically not something to retry.
		if errors.Is(err, errCancelled) || (callCtx.Err() != nil && ctx.Err() == nil) {
			return errSkip
		}
		return err
	}
	if len(resp.Segments) == 0 {
		return fmt.Errorf("worker returned 0 segments")
	}

	// Keep the raw worker output. When alignment looks wrong, the original is
	// what you actually need to debug it.
	if rawDir != "" {
		if err := os.MkdirAll(rawDir, 0o755); err == nil {
			raw, _ := json.MarshalIndent(resp, "", " ")
			p := filepath.Join(rawDir, fmt.Sprintf("%d.json", id))
			if err := os.WriteFile(p, raw, 0o644); err != nil {
				log.Printf("WARNING item %d: writing raw transcript: %v", id, err)
			}
		}
	}

	// Cancellation is the only thing that can move an item out of
	// 'transcribing' while the worker is thinking. Nothing can interrupt that
	// request, so the check happens here instead: a result for an item that is
	// no longer being transcribed is dropped rather than written back.
	var current string
	if err := pool.QueryRowContext(ctx,
		`SELECT status FROM items WHERE id=?`, id).Scan(&current); err != nil {
		return err
	}
	if current != "transcribing" {
		log.Printf("transcribe item %d: cancelled while running (now %s), discarding result",
			id, current)
		return nil
	}

	return persist(ctx, pool, id, lang, resp)
}

func callWorker(ctx context.Context, workerURL, audioPath, language, model string) (*TranscriptResponse, error) {
	body, err := json.Marshal(map[string]string{
		"audio_path": audioPath, "language": language, "model": model,
	})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(workerURL, "/")+"/transcribe", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	// Transcription on CPU is slow; a 10 minute episode with kb-whisper-small
	// can take several minutes. Give it room.
	client := &http.Client{Timeout: 2 * time.Hour}
	res, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("worker: %w", err)
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusConflict {
		return nil, errCancelled
	}
	if res.StatusCode != http.StatusOK {
		var buf bytes.Buffer
		buf.ReadFrom(res.Body)
		return nil, fmt.Errorf("worker: status %d: %s", res.StatusCode, strings.TrimSpace(buf.String()))
	}
	var out TranscriptResponse
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("worker: decode: %w", err)
	}
	return &out, nil
}

// persist writes segments and tokens in one transaction, resolving each token's
// lemma as it goes so the read path never does morphology work.
func persist(ctx context.Context, pool *sql.DB, itemID int, lang string, tr *TranscriptResponse) error {
	tx, err := pool.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback() //nolint:errcheck

	// Re-transcribing an item replaces its previous alignment wholesale.
	if _, err := tx.ExecContext(ctx, `DELETE FROM segments WHERE item_id=?`, itemID); err != nil {
		return err
	}

	tokenIdx := 0
	lemmaCache := map[string]any{}

	for segIdx, seg := range tr.Segments {
		text := strings.TrimSpace(seg.Text)
		if text == "" {
			continue
		}
		var segID int
		err := tx.QueryRowContext(ctx, `
			INSERT INTO segments (item_id, idx, start_ms, end_ms, text)
			VALUES (?,?,?,?,?) RETURNING id`,
			itemID, segIdx, toMS(seg.Start), toMS(seg.End), text).Scan(&segID)
		if err != nil {
			return err
		}

		for _, w := range seg.Words {
			surface := strings.TrimSpace(w.Word)
			if surface == "" {
				continue
			}
			norm := lexicon.Normalize(surface)
			isWord := norm != "" && hasLetter(norm)

			var lemma any
			if isWord {
				if cached, ok := lemmaCache[norm]; ok {
					lemma = cached
				} else {
					lemma = resolveLemmaTx(ctx, tx, lang, norm)
					lemmaCache[norm] = lemma
				}
			}

			_, err := tx.ExecContext(ctx, `
				INSERT INTO tokens (item_id, segment_id, idx, surface, normalized,
				                    start_ms, end_ms, is_word, lemma)
				VALUES (?,?,?,?,?,?,?,?,?)`,
				itemID, segID, tokenIdx, surface, norm,
				toMS(w.Start), toMS(w.End), isWord, lemma)
			if err != nil {
				return err
			}
			tokenIdx++
		}
	}

	if _, err := tx.ExecContext(ctx,
		`UPDATE items SET status='ready', error=NULL,
		        fetched_at=COALESCE(fetched_at, strftime('%Y-%m-%d %H:%M:%S','now'))
		 WHERE id=?`, itemID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	log.Printf("persist item %d: %d segments, %d tokens", itemID, len(tr.Segments), tokenIdx)
	return nil
}

// resolveLemmaTx picks a single lemma to store on the token. Ambiguity is
// preserved at lookup time (the API returns every candidate); this field is
// only a fast path, so prefer an exact self-match and otherwise take the
// alphabetically first candidate for determinism.
func resolveLemmaTx(ctx context.Context, tx *sql.Tx, lang, norm string) any {
	rows, err := tx.QueryContext(ctx, `
		SELECT lemma FROM forms WHERE language_code = ? AND form = ?
		UNION
		SELECT lemma FROM lexemes WHERE language_code = ? AND lemma = ?
		ORDER BY 1`, lang, norm, lang, norm)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var candidates []string
	for rows.Next() {
		var l string
		if err := rows.Scan(&l); err != nil {
			return nil
		}
		candidates = append(candidates, l)
	}
	if len(candidates) == 0 {
		return nil
	}
	for _, c := range candidates {
		if c == norm {
			return c
		}
	}
	return candidates[0]
}

// itemLanguage returns the language code an item's content is in.
func itemLanguage(ctx context.Context, pool *sql.DB, itemID int) string {
	var code string
	err := pool.QueryRowContext(ctx, `
		SELECT s.language_code FROM items i
		JOIN sources s ON s.id = i.source_id WHERE i.id = ?`, itemID).Scan(&code)
	if err != nil || code == "" {
		return lexicon.DefaultLanguage
	}
	return code
}

// toMS converts float seconds to integer milliseconds.
func toMS(sec float64) int {
	if sec < 0 {
		return 0
	}
	return int(sec*1000 + 0.5)
}

func hasLetter(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) {
			return true
		}
	}
	return false
}
