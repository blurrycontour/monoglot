package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adityasingh/svenska/api/internal/config"
	"github.com/adityasingh/svenska/api/internal/db"
	"github.com/adityasingh/svenska/api/internal/httpapi"
	"github.com/adityasingh/svenska/api/internal/ingest"
	"github.com/adityasingh/svenska/api/internal/lexicon"
	"github.com/adityasingh/svenska/api/internal/srclient"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("")

	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}

	cfg := config.Load()
	ctx := context.Background()

	switch cmd {
	case "serve":
		serve(ctx, cfg)
	case "migrate":
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		if err := db.Migrate(ctx, pool); err != nil {
			log.Fatalf("migrate: %v", err)
		}
		log.Println("migrate: up to date")
	case "import-dictionary":
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		lang := langArg()
		p, ok := lexicon.Dictionary(lang)
		if !ok {
			log.Fatalf("import-dictionary: no dictionary provider registered for %q", lang)
		}
		if alreadyImported(ctx, pool, "lexemes", "language_code = '"+lang+"' AND origin = 'folkets'") {
			return
		}
		path := mustFetch(ctx, p.SourceURL(), p.CacheName(), localPath(2))
		if err := p.Import(ctx, pool, lang, path); err != nil {
			log.Fatalf("import-dictionary: %v", err)
		}
	case "import-morphology":
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		lang := langArg()
		p, ok := lexicon.Morphology(lang)
		if !ok {
			log.Fatalf("import-morphology: no morphology provider registered for %q", lang)
		}
		if alreadyImported(ctx, pool, "forms", "language_code = '"+lang+"'") {
			return
		}
		path := mustFetch(ctx, p.SourceURL(), p.CacheName(), localPath(2))
		if err := p.Import(ctx, pool, lang, path); err != nil {
			log.Fatalf("import-morphology: %v", err)
		}
	case "ingest":
		// Optional stage argument runs just one step of the state machine,
		// which is what you want when debugging: "ingest download" must not
		// drag the slow transcription stage along with it.
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		switch argAt(2) {
		case "", "all":
			ingest.NewRunner(pool, cfg).Run(ctx, "cli")
		case "discover":
			if err := ingest.Discover(ctx, pool); err != nil {
				log.Fatalf("discover: %v", err)
			}
		case "download":
			if err := ingest.DownloadPending(ctx, pool, cfg.AudioDir, 10); err != nil {
				log.Fatalf("download: %v", err)
			}
		case "transcribe":
			if err := ingest.TranscribePending(ctx, pool, cfg.WorkerURL, cfg.RawDir, 5); err != nil {
				log.Fatalf("transcribe: %v", err)
			}
		default:
			log.Fatalf("ingest: unknown stage %q (discover|download|transcribe|all)", argAt(2))
		}
	case "find-program":
		// Diagnostic for the case the spec says to ask about: if the Klartext
		// program id ever stops resolving, this shows what SR actually has.
		name := argAt(2)
		if name == "" {
			name = "klartext"
		}
		hits, err := srclient.New().FindProgram(ctx, name)
		if err != nil {
			log.Fatalf("find-program: %v", err)
		}
		if len(hits) == 0 {
			log.Fatalf("find-program: no SR program matching %q", name)
		}
		for _, p := range hits {
			fmt.Printf("%d\t%s\n", p.ID, p.Name)
		}
	default:
		log.Fatalf("unknown command %q (serve|migrate|import-dictionary|import-morphology|ingest|find-program)", cmd)
	}
}

// alreadyImported reports whether a bootstrap import can be skipped. Both
// imports are row-level idempotent, but re-running them still costs a 250MB
// download on a fresh checkout plus a full re-parse and 1.7M redundant upserts.
// Pass --force to reimport anyway (after a dataset update, say).
func alreadyImported(ctx context.Context, pool *pgxpool.Pool, table, where string) bool {
	if hasFlag("--force") {
		log.Printf("%s: --force given, reimporting", table)
		return false
	}
	q := "SELECT count(*) FROM " + table
	if where != "" {
		q += " WHERE " + where
	}
	var n int
	if err := pool.QueryRow(ctx, q).Scan(&n); err != nil {
		// Cannot tell: do the work rather than silently skip it.
		return false
	}
	if n == 0 {
		return false
	}
	log.Printf("%s already populated (%d rows), skipping import. Use --force to reimport.", table, n)
	return true
}

func hasFlag(name string) bool {
	for _, a := range os.Args[1:] {
		if a == name {
			return true
		}
	}
	return false
}

// langArg reads --lang=xx, defaulting to the single language this instance
// currently ships with.
func langArg() string {
	for _, a := range os.Args[1:] {
		if strings.HasPrefix(a, "--lang=") {
			return strings.TrimPrefix(a, "--lang=")
		}
	}
	return lexicon.DefaultLanguage
}

// localPath returns a positional path override, ignoring flags.
func localPath(i int) string {
	if v := argAt(i); v != "" && !strings.HasPrefix(v, "-") {
		return v
	}
	return ""
}

func argAt(i int) string {
	if len(os.Args) > i {
		return os.Args[i]
	}
	return ""
}

func serve(ctx context.Context, cfg config.Config) {
	pool := mustConnect(ctx, cfg)
	defer pool.Close()

	if err := db.Migrate(ctx, pool); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	if cfg.AuthToken == "" || cfg.AuthToken == "changeme-run-openssl-rand-hex-32" {
		log.Println("WARNING: AUTH_TOKEN is unset or default. Set it before using this on the network.")
	}

	runner := ingest.NewRunner(pool, cfg)
	cronCtx, cancelCron := context.WithCancel(ctx)
	defer cancelCron()
	runner.StartCron(cronCtx)
	if cfg.IngestOnStart {
		runner.Trigger("startup")
	}

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           httpapi.NewServer(pool, cfg, runner).Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		// No WriteTimeout: audio responses are large and streamed, and a
		// write deadline would truncate them mid-playback.
	}

	go func() {
		log.Printf("api: listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("api: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	log.Println("api: shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("api: shutdown: %v", err)
	}
}

func mustConnect(ctx context.Context, cfg config.Config) *pgxpool.Pool {
	// Postgres may still be starting when this container comes up; retry
	// briefly rather than crash-looping the whole compose stack.
	var lastErr error
	for i := 0; i < 30; i++ {
		pool, err := db.Connect(ctx, cfg.DatabaseURL)
		if err == nil {
			return pool
		}
		lastErr = err
		time.Sleep(time.Second)
	}
	log.Fatalf("db: could not connect: %v", lastErr)
	return nil
}

// mustFetch returns a local path for url, downloading it to the cache dir
// unless an explicit local override path was given. Idempotent: an existing
// non-empty file is reused, so re-running a bootstrap import is cheap.
func mustFetch(ctx context.Context, url, name, override string) string {
	if override != "" {
		return override
	}
	dir := os.Getenv("DATA_CACHE_DIR")
	if dir == "" {
		dir = "/data/cache"
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Fatalf("fetch %s: %v", name, err)
	}
	dest := filepath.Join(dir, name)
	if st, err := os.Stat(dest); err == nil && st.Size() > 0 {
		log.Printf("fetch %s: using cached %s (%d bytes)", name, dest, st.Size())
		return dest
	}

	log.Printf("fetch %s: downloading %s", name, url)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		log.Fatalf("fetch %s: %v", name, err)
	}
	req.Header.Set("User-Agent", "svenska-listening-trainer/1.0 (personal use)")

	client := &http.Client{Timeout: 30 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		log.Fatalf("fetch %s: %v", name, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		log.Fatalf("fetch %s: status %d", name, resp.StatusCode)
	}

	tmp := dest + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		log.Fatalf("fetch %s: %v", name, err)
	}
	n, err := io.Copy(f, resp.Body)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		log.Fatalf("fetch %s: %v", name, err)
	}
	if err := os.Rename(tmp, dest); err != nil {
		log.Fatalf("fetch %s: %v", name, err)
	}
	log.Printf("fetch %s: %d bytes -> %s", name, n, dest)
	return dest
}
