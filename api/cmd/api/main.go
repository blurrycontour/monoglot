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

	"database/sql"

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
		if importDone(ctx, pool, "dictionary", lang) {
			return
		}
		path := mustFetch(ctx, p.SourceURL(), p.CacheName(), localPath(2))
		if err := p.Import(ctx, pool, lang, path); err != nil {
			log.Fatalf("import-dictionary: %v", err)
		}
		markImported(ctx, pool, "dictionary", lang, countRows(ctx, pool, "lexemes"))
		log.Println("refreshing query statistics")
		if err := db.Analyze(ctx, pool); err != nil {
			log.Printf("WARNING analyze: %v", err)
		}
	case "import-morphology":
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		lang := langArg()
		p, ok := lexicon.Morphology(lang)
		if !ok {
			log.Fatalf("import-morphology: no morphology provider registered for %q", lang)
		}
		if importDone(ctx, pool, "morphology", lang) {
			return
		}
		path := mustFetch(ctx, p.SourceURL(), p.CacheName(), localPath(2))
		if err := p.Import(ctx, pool, lang, path); err != nil {
			log.Fatalf("import-morphology: %v", err)
		}
		markImported(ctx, pool, "morphology", lang, countRows(ctx, pool, "forms"))
		log.Println("refreshing query statistics")
		if err := db.Analyze(ctx, pool); err != nil {
			log.Printf("WARNING analyze: %v", err)
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
	case "import-postgres":
		// One-shot migration off Postgres. Idempotent, so a partial run can be
		// repeated safely.
		pgURL := argAt(2)
		if pgURL == "" {
			pgURL = os.Getenv("PG_URL")
		}
		if pgURL == "" {
			log.Fatal("import-postgres: pass the Postgres URL as an argument or set PG_URL")
		}
		conn := mustConnect(ctx, cfg)
		defer conn.Close()
		if err := db.Migrate(ctx, conn); err != nil {
			log.Fatalf("migrate: %v", err)
		}
		if err := importFromPostgres(ctx, conn, pgURL); err != nil {
			log.Fatalf("import-postgres: %v", err)
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
		log.Fatalf("unknown command %q (serve|migrate|import-dictionary|import-morphology|ingest|import-postgres|find-program)", cmd)
	}
}

// importDone reports whether a bootstrap import has already completed.
//
// Tracked explicitly rather than inferred from row counts: Folkets writes to
// both lexemes and forms, so counting forms cannot distinguish "SALDO has been
// imported" from "the dictionary contributed some inflections".
// Pass --force to reimport, after a dataset update say.
func importDone(ctx context.Context, conn *sql.DB, kind, lang string) bool {
	if hasFlag("--force") {
		log.Printf("%s: --force given, reimporting", kind)
		return false
	}
	var n int
	err := conn.QueryRowContext(ctx,
		`SELECT row_count FROM imports WHERE kind = ? AND language_code = ?`,
		kind, lang).Scan(&n)
	if err != nil || n == 0 {
		return false
	}
	log.Printf("%s already imported for %s (%d rows), skipping. Use --force to reimport.",
		kind, lang, n)
	return true
}

func markImported(ctx context.Context, conn *sql.DB, kind, lang string, rows int) {
	if _, err := conn.ExecContext(ctx, `
		INSERT INTO imports (kind, language_code, row_count)
		VALUES (?,?,?)
		ON CONFLICT (kind, language_code) DO UPDATE SET
		  row_count = excluded.row_count,
		  completed_at = strftime('%Y-%m-%d %H:%M:%S','now')`,
		kind, lang, rows); err != nil {
		log.Printf("WARNING recording %s import: %v", kind, err)
	}
}

func countRows(ctx context.Context, conn *sql.DB, table string) int {
	var n int
	// table is a constant at every call site, never user input.
	conn.QueryRowContext(ctx, "SELECT count(*) FROM "+table).Scan(&n)
	return n
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

	db.EnsureStats(ctx, pool)
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

// mustConnect opens the database and brings the schema up to date.
//
// Migrations run here rather than only in serve: any command may be the first
// thing a fresh install runs, and an import against a schemaless database
// fails with a confusing "no such table".
func mustConnect(ctx context.Context, cfg config.Config) *sql.DB {
	conn, err := db.Connect(ctx, cfg.DatabasePath)
	if err != nil {
		log.Fatalf("db: could not open %s: %v", cfg.DatabasePath, err)
	}
	if err := db.Migrate(ctx, conn); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	return conn
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
