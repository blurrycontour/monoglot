// Package bootstrap brings an empty instance up to a working one.
//
// This used to live in bootstrap.sh, which meant a new machine needed the repo
// checked out before it could run anything. Everything that script did to the
// database now happens inside the API on first start, so a production host
// needs only docker-compose.yml and a .env: `docker compose up -d` is the
// whole install.
//
// It is idempotent and safe to re-enter: each step records itself in the
// imports table, and the audio pipeline is a state machine that already
// tolerates being restarted at any point.
package bootstrap

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/adityasingh/svenska/api/internal/db"
	"github.com/adityasingh/svenska/api/internal/lexicon"
)

// Status is what the app shows while a fresh instance is still filling up. A
// first start downloads ~250MB of SALDO and imports a million word forms; with
// no signal for that the library screen just looks broken.
type Status struct {
	Running  bool   `json:"running"`
	Step     string `json:"step,omitempty"`
	Error    string `json:"error,omitempty"`
	Complete bool   `json:"complete"`
}

var (
	mu    sync.RWMutex
	state Status
)

func Get() Status {
	mu.RLock()
	defer mu.RUnlock()
	return state
}

func setStep(step string) {
	mu.Lock()
	state = Status{Running: true, Step: step}
	mu.Unlock()
	log.Printf("bootstrap: %s", step)
}

func finish(err error) {
	mu.Lock()
	if err != nil {
		state = Status{Error: err.Error()}
		log.Printf("bootstrap: FAILED: %v", err)
	} else {
		state = Status{Complete: true}
		log.Printf("bootstrap: ready")
	}
	mu.Unlock()
}

// Needed reports whether anything is missing. Cheap enough to call on every
// start; on an established instance it is two indexed reads.
func Needed(ctx context.Context, pool *sql.DB, lang string) bool {
	return !imported(ctx, pool, "dictionary", lang) ||
		!imported(ctx, pool, "morphology", lang)
}

// Run imports whatever is missing, then hands back. The caller decides what to
// do about content; the pipeline's own cron picks that up.
//
// Errors are logged and reported through Status rather than returned as fatal:
// a dictionary that failed to download must not stop the server from serving
// the episodes it already has.
func Run(ctx context.Context, pool *sql.DB, lang string) {
	if !Needed(ctx, pool, lang) {
		finish(nil)
		return
	}

	if !imported(ctx, pool, "dictionary", lang) {
		setStep("importing dictionary")
		if err := ImportDictionary(ctx, pool, lang, ""); err != nil {
			finish(fmt.Errorf("dictionary: %w", err))
			return
		}
	}

	if !imported(ctx, pool, "morphology", lang) {
		setStep("importing word forms (large download, several minutes)")
		if err := ImportMorphology(ctx, pool, lang, ""); err != nil {
			finish(fmt.Errorf("morphology: %w", err))
			return
		}
	}

	setStep("refreshing query statistics")
	if err := db.Analyze(ctx, pool); err != nil {
		log.Printf("bootstrap: WARNING analyze: %v", err)
	}
	finish(nil)
}

// ImportDictionary is a no-op when the import is already recorded, unless
// force is set by the caller passing an explicit local path.
func ImportDictionary(ctx context.Context, pool *sql.DB, lang, localPath string) error {
	p, ok := lexicon.Dictionary(lang)
	if !ok {
		return fmt.Errorf("no dictionary provider registered for %q", lang)
	}
	path, err := Fetch(ctx, p.SourceURL(), p.CacheName(), localPath)
	if err != nil {
		return err
	}
	if err := p.Import(ctx, pool, lang, path); err != nil {
		return err
	}
	MarkImported(ctx, pool, "dictionary", lang, countRows(ctx, pool, "lexemes"))
	return nil
}

func ImportMorphology(ctx context.Context, pool *sql.DB, lang, localPath string) error {
	p, ok := lexicon.Morphology(lang)
	if !ok {
		return fmt.Errorf("no morphology provider registered for %q", lang)
	}
	path, err := Fetch(ctx, p.SourceURL(), p.CacheName(), localPath)
	if err != nil {
		return err
	}
	if err := p.Import(ctx, pool, lang, path); err != nil {
		return err
	}
	MarkImported(ctx, pool, "morphology", lang, countRows(ctx, pool, "forms"))
	return nil
}

func imported(ctx context.Context, pool *sql.DB, kind, lang string) bool {
	var n int
	err := pool.QueryRowContext(ctx,
		`SELECT row_count FROM imports WHERE kind = ? AND language_code = ?`,
		kind, lang).Scan(&n)
	return err == nil && n > 0
}

func MarkImported(ctx context.Context, pool *sql.DB, kind, lang string, rows int) {
	if _, err := pool.ExecContext(ctx, `
		INSERT INTO imports (kind, language_code, row_count)
		VALUES (?,?,?)
		ON CONFLICT (kind, language_code) DO UPDATE SET
		  row_count = excluded.row_count,
		  completed_at = strftime('%Y-%m-%d %H:%M:%S','now')`,
		kind, lang, rows); err != nil {
		log.Printf("WARNING recording %s import: %v", kind, err)
	}
}

// Imported is the public form of the guard, for the CLI's skip message.
func Imported(ctx context.Context, pool *sql.DB, kind, lang string) bool {
	return imported(ctx, pool, kind, lang)
}

func countRows(ctx context.Context, pool *sql.DB, table string) int {
	var n int
	pool.QueryRowContext(ctx, "SELECT count(*) FROM "+table).Scan(&n)
	return n
}

// Fetch returns a local path for url, downloading to the cache dir unless an
// explicit local override was given. Idempotent: an existing non-empty file is
// reused, so re-running an import after a crash is cheap.
func Fetch(ctx context.Context, url, name, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	dir := os.Getenv("DATA_CACHE_DIR")
	if dir == "" {
		dir = "/data/cache"
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	dest := filepath.Join(dir, name)
	if st, err := os.Stat(dest); err == nil && st.Size() > 0 {
		log.Printf("fetch %s: using cached %s (%d bytes)", name, dest, st.Size())
		return dest, nil
	}

	log.Printf("fetch %s: downloading %s", name, url)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "monoglot/1.0 (personal use)")

	client := &http.Client{Timeout: 30 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("fetch %s: status %d", name, resp.StatusCode)
	}

	// Written to .part and renamed: a download killed halfway must not leave a
	// truncated file that the next run happily reuses.
	tmp := dest + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	n, err := io.Copy(f, resp.Body)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return "", err
	}
	log.Printf("fetch %s: %d bytes -> %s", name, n, dest)
	return dest, nil
}
