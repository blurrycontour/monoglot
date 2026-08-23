// Package db owns the SQLite connection and schema migrations.
//
// SQLite rather than Postgres: this is a single-user, single-writer app whose
// largest table is ~1M rows of morphology. SQLite handles that comfortably,
// removes a service and its container, and makes lookups faster by removing
// the socket round-trip. modernc.org/sqlite is a pure-Go driver, which keeps
// the API image CGO-free.
package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed all:migrations
var migrationsFS embed.FS

// TimeLayout is how timestamps are stored: SQLite's canonical text format, in
// UTC. Chosen over ISO-8601 with a "T" and "Z" because SQLite's own date()
// and datetime() functions parse this directly, which the queries rely on.
const TimeLayout = "2006-01-02 15:04:05"

func FormatTime(t time.Time) string { return t.UTC().Format(TimeLayout) }

// ParseTime reads a stored timestamp back. Returns the zero time on anything
// unparseable rather than failing a whole row.
func ParseTime(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	for _, layout := range []string{TimeLayout, time.RFC3339, "2006-01-02 15:04:05.999999999-07:00"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.UTC()
		}
	}
	return time.Time{}
}

// Connect opens the database, creating its directory if needed.
func Connect(ctx context.Context, path string) (*sql.DB, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, err
		}
	}

	// _txlock=immediate makes write transactions take the write lock up front,
	// which avoids SQLITE_BUSY upgrade deadlocks between the ingest worker and
	// an HTTP request.
	dsn := path + "?_pragma=busy_timeout(10000)" +
		"&_pragma=journal_mode(WAL)" +
		"&_pragma=synchronous(NORMAL)" +
		"&_pragma=foreign_keys(ON)" +
		"&_txlock=immediate"

	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}

	// SQLite takes one writer at a time. More open connections buys nothing
	// for writes and risks lock contention; reads are fast enough that a small
	// pool is ample for one user.
	conn.SetMaxOpenConns(4)
	conn.SetMaxIdleConns(4)
	conn.SetConnMaxLifetime(0)

	if err := conn.PingContext(ctx); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// Migrate applies every embedded .sql file in filename order, recording each
// so re-running is a no-op.
func Migrate(ctx context.Context, conn *sql.DB) error {
	if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version TEXT PRIMARY KEY,
		applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')))`); err != nil {
		return err
	}

	entries, err := migrationsFS.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)

	for _, name := range names {
		var exists bool
		if err := conn.QueryRowContext(ctx,
			`SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version=?)`, name,
		).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		body, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			return err
		}
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %s: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations (version) VALUES (?)`, name); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		log.Printf("migrate: applied %s", name)
	}
	return nil
}

// Analyze refreshes the query planner's statistics.
//
// This is not optional after a bulk import. Without sqlite_stat1 the planner
// drives the lookup join from lexemes and scans every entry for the language:
// measured at 12.5ms per lookup against 0.006ms once analysed, a 2000x
// difference on the app's most latency-sensitive path.
func Analyze(ctx context.Context, conn *sql.DB) error {
	_, err := conn.ExecContext(ctx, "ANALYZE")
	return err
}

// Optimize is the cheap incremental form, recommended to run periodically and
// before closing a connection. It is a no-op when nothing needs updating.
func Optimize(ctx context.Context, conn *sql.DB) {
	if _, err := conn.ExecContext(ctx, "PRAGMA optimize"); err != nil {
		log.Printf("WARNING pragma optimize: %v", err)
	}
}

// EnsureStats runs a full ANALYZE when the database has never been analysed,
// so an instance that was populated before this existed heals itself on
// startup rather than serving slow lookups forever.
func EnsureStats(ctx context.Context, conn *sql.DB) {
	var n int
	err := conn.QueryRowContext(ctx,
		`SELECT count(*) FROM sqlite_master WHERE name = 'sqlite_stat1'`).Scan(&n)
	if err != nil || n > 0 {
		Optimize(ctx, conn)
		return
	}
	log.Println("db: no query statistics, running ANALYZE (one time, a few seconds)")
	if err := Analyze(ctx, conn); err != nil {
		log.Printf("WARNING analyze: %v", err)
	}
}

// FileSize reports the on-disk size of the database, including its WAL.
func FileSize(path string) int64 {
	var total int64
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if fi, err := os.Stat(path + suffix); err == nil {
			total += fi.Size()
		}
	}
	return total
}

// NullTime scans a stored timestamp. SQLite returns TEXT for these columns, so
// the driver cannot produce a time.Time on its own; this parses at the scan
// boundary and keeps the rest of the code working in time.Time.
type NullTime struct {
	Time  time.Time
	Valid bool
}

func (n *NullTime) Scan(v any) error {
	n.Time, n.Valid = time.Time{}, false
	switch t := v.(type) {
	case nil:
		return nil
	case time.Time:
		n.Time, n.Valid = t.UTC(), true
	case string:
		if t == "" {
			return nil
		}
		parsed := ParseTime(t)
		if !parsed.IsZero() {
			n.Time, n.Valid = parsed, true
		}
	case []byte:
		return n.Scan(string(t))
	}
	return nil
}

// Ptr returns a pointer suitable for a JSON field that omits empty values.
func (n NullTime) Ptr() *time.Time {
	if !n.Valid {
		return nil
	}
	t := n.Time
	return &t
}
