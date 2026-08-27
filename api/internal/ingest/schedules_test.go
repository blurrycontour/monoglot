package ingest

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/config"
	"github.com/blurrycontour/monoglot/api/internal/db"
)

func newTestRunner(t *testing.T) *Runner {
	t.Helper()
	ctx := context.Background()
	pool, err := db.Connect(ctx, filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(func() { pool.Close() })
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return NewRunner(pool, config.Config{})
}

// The loop sleeps until the soonest time, not the first row or the last one.
// Getting this wrong is invisible until the day a second schedule is added.
func TestNextRunPicksTheSoonest(t *testing.T) {
	r := newTestRunner(t)
	ctx := context.Background()

	for _, at := range [][2]int{{3, 30}, {14, 0}, {22, 15}} {
		if _, err := AddSchedule(ctx, r.pool, at[0], at[1]); err != nil {
			t.Fatal(err)
		}
	}

	cases := []struct {
		now  string
		want string
	}{
		{"2026-05-01T01:00:00", "2026-05-01T03:30:00"},
		{"2026-05-01T09:00:00", "2026-05-01T14:00:00"},
		{"2026-05-01T18:00:00", "2026-05-01T22:15:00"},
		// Past the last one, so it rolls to the first of the next day.
		{"2026-05-01T23:00:00", "2026-05-02T03:30:00"},
		// Exactly on a scheduled minute: that one has just fired, take the
		// next. Otherwise the loop would arm a zero-length timer and spin.
		{"2026-05-01T14:00:00", "2026-05-01T22:15:00"},
	}
	for _, c := range cases {
		now := mustParse(t, c.now)
		got, ok := r.NextRun(ctx, now)
		if !ok {
			t.Fatalf("%s: want a next run", c.now)
		}
		if want := mustParse(t, c.want); !got.Equal(want) {
			t.Errorf("at %s: want %s, got %s", c.now, want, got)
		}
	}
}

func TestNextRunWithNoSchedule(t *testing.T) {
	r := newTestRunner(t)
	if _, ok := r.NextRun(context.Background(), time.Now()); ok {
		t.Fatal("a server with no schedule must have no next run")
	}
}

func mustParse(t *testing.T, s string) time.Time {
	t.Helper()
	at, err := time.ParseInLocation("2006-01-02T15:04:05", s, time.Local)
	if err != nil {
		t.Fatal(err)
	}
	return at
}
