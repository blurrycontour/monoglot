package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"database/sql"

	"github.com/blurrycontour/monoglot/api/internal/bootstrap"
	"github.com/blurrycontour/monoglot/api/internal/config"
	"github.com/blurrycontour/monoglot/api/internal/db"
	"github.com/blurrycontour/monoglot/api/internal/httpapi"
	"github.com/blurrycontour/monoglot/api/internal/ingest"
	"github.com/blurrycontour/monoglot/api/internal/lexicon"
	"github.com/blurrycontour/monoglot/api/internal/srclient"
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
		runImport(ctx, pool, "dictionary", langArg())
	case "import-morphology":
		pool := mustConnect(ctx, cfg)
		defer pool.Close()
		runImport(ctx, pool, "morphology", langArg())
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

	// A fresh instance fills itself in: dictionary, word forms, then the first
	// fetch of episodes. In the background, because the SALDO download alone
	// runs for minutes and the server must answer its health check long before
	// that finishes.
	if bootstrap.Needed(ctx, pool, lexicon.DefaultLanguage) {
		log.Println("bootstrap: first start, filling in the dictionary")
	}
	if cfg.AuthToken == "" || cfg.AuthToken == "changeme-run-openssl-rand-hex-32" {
		log.Println("WARNING: AUTH_TOKEN is unset or default. Set it before using this on the network.")
	}

	runner := ingest.NewRunner(pool, cfg)
	cronCtx, cancelCron := context.WithCancel(ctx)
	defer cancelCron()
	runner.StartCron(cronCtx)

	firstRun := bootstrap.Needed(ctx, pool, lexicon.DefaultLanguage)
	go func() {
		bootstrap.Run(cronCtx, pool, lexicon.DefaultLanguage)
		// Only on a genuinely empty instance: on every later start this would
		// pull a full ingest at boot, which is what the nightly cron is for.
		if firstRun {
			runner.Trigger("bootstrap")
		}
	}()
	if cfg.IngestOnStart && !firstRun {
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

// runImport is the CLI face of a bootstrap import. The server does the same
// work on first start; this exists for forcing a reimport and for running one
// against a local file.
func runImport(ctx context.Context, pool *sql.DB, kind, lang string) {
	if bootstrap.Imported(ctx, pool, kind, lang) && !hasFlag("--force") {
		log.Printf("%s already imported for %s, skipping. Use --force to reimport.", kind, lang)
		return
	}

	// A path argument overrides the download, for working offline or against a
	// pinned copy of the source data.
	override := ""
	if v := argAt(2); v != "" && !strings.HasPrefix(v, "-") {
		override = v
	}

	var err error
	switch kind {
	case "dictionary":
		err = bootstrap.ImportDictionary(ctx, pool, lang, override)
	case "morphology":
		err = bootstrap.ImportMorphology(ctx, pool, lang, override)
	}
	if err != nil {
		log.Fatalf("import-%s: %v", kind, err)
	}

	log.Println("refreshing query statistics")
	if err := db.Analyze(ctx, pool); err != nil {
		log.Printf("WARNING analyze: %v", err)
	}
}
