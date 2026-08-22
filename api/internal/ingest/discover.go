// Package ingest implements the nightly content pipeline as a state machine
// over items.status:
//
//	new -> downloading -> downloaded -> transcribing -> ready
//	                          |             |
//	                        failed <--------+
//
// Each stage is independently retryable. This matters because transcription is
// slow: a failure at the alignment step must never force a re-download.
package ingest

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adityasingh/svenska/api/internal/srclient"
)

type Source struct {
	ID      int
	Slug    string
	Name    string
	Kind    string
	Config  json.RawMessage
	Enabled bool
}

// Discover fetches each enabled source's feed and inserts new items with
// status='new', deduping on (source_id, external_id).
func Discover(ctx context.Context, pool *pgxpool.Pool) error {
	rows, err := pool.Query(ctx,
		`SELECT id, slug, name, kind, config, enabled FROM sources WHERE enabled ORDER BY id`)
	if err != nil {
		return err
	}
	var sources []Source
	for rows.Next() {
		var s Source
		if err := rows.Scan(&s.ID, &s.Slug, &s.Name, &s.Kind, &s.Config, &s.Enabled); err != nil {
			rows.Close()
			return err
		}
		sources = append(sources, s)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	// One source failing must not stop the others. SR in particular is
	// unmaintained and will misbehave; log loudly and carry on.
	var firstErr error
	for _, s := range sources {
		var n int
		var err error
		switch s.Kind {
		case "sr_api":
			n, err = discoverSR(ctx, pool, s)
		case "rss":
			n, err = discoverRSS(ctx, pool, s)
		default:
			err = fmt.Errorf("unknown source kind %q", s.Kind)
		}
		if err != nil {
			log.Printf("ERROR discover %s: %v", s.Slug, err)
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		log.Printf("discover %s: %d new item(s)", s.Slug, n)
		if _, err := pool.Exec(ctx,
			`UPDATE sources SET last_fetched=now() WHERE id=$1`, s.ID); err != nil {
			log.Printf("ERROR discover %s: recording last_fetched: %v", s.Slug, err)
		}
	}
	return firstErr
}

func discoverSR(ctx context.Context, pool *pgxpool.Pool, s Source) (int, error) {
	var cfg struct {
		ProgramID   int    `json:"program_id"`
		ProgramName string `json:"program_name"`
		// SR re-publishes the same Klartext episode for each airing (19:00
		// and 21:00), with a different episode id and audio URL but identical
		// content. The spec's (source_id, external_id) key cannot catch that,
		// so programmes known to air once per day opt into a date-based check.
		OnePerDay bool `json:"one_per_day"`
	}
	if err := json.Unmarshal(s.Config, &cfg); err != nil {
		return 0, err
	}
	if cfg.ProgramID == 0 {
		return 0, fmt.Errorf("source %s has no program_id in config", s.Slug)
	}

	c := srclient.New()
	eps, err := c.Episodes(ctx, cfg.ProgramID, 20)
	if err != nil {
		return 0, err
	}
	if len(eps) == 0 {
		// Not an error per se, but worth shouting about: an empty feed for a
		// daily program means the program id is probably wrong.
		log.Printf("WARNING discover %s: SR returned 0 episodes for program %d", s.Slug, cfg.ProgramID)
	}

	n := 0
	for _, e := range eps {
		audio := e.Audio()
		if audio == nil {
			log.Printf("discover %s: episode %d has no audio, skipping", s.Slug, e.ID)
			continue
		}
		var pub any
		if !e.PublishDateUTC.IsZero() {
			pub = e.PublishDateUTC.Time
		}
		var durMS any
		if audio.Duration > 0 {
			durMS = audio.Duration * 1000
		}

		if cfg.OnePerDay && pub != nil {
			dupe, err := hasItemOnDate(ctx, pool, s.ID, e.PublishDateUTC.Time)
			if err != nil {
				return n, err
			}
			if dupe {
				log.Printf("discover %s: episode %d duplicates an existing episode for %s, skipping",
					s.Slug, e.ID, e.PublishDateUTC.Format("2006-01-02"))
				continue
			}
		}

		inserted, err := insertItem(ctx, pool, s.ID, fmt.Sprint(e.ID),
			e.Title, e.Description, pub, audio.URL, durMS)
		if err != nil {
			return n, err
		}
		if inserted {
			n++
		}
	}
	return n, nil
}

// hasItemOnDate reports whether the source already has an item published on
// the same UTC date. Used only by sources flagged one_per_day.
func hasItemOnDate(ctx context.Context, pool *pgxpool.Pool, sourceID int, published time.Time) (bool, error) {
	var exists bool
	err := pool.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM items
			WHERE source_id = $1 AND published_at::date = $2::date)`,
		sourceID, published).Scan(&exists)
	return exists, err
}

// insertItem inserts one item, returning whether it was actually new.
func insertItem(ctx context.Context, pool *pgxpool.Pool, sourceID int,
	externalID, title, desc string, publishedAt any, audioURL string, durationMS any) (bool, error) {

	var id int
	err := pool.QueryRow(ctx, `
		INSERT INTO items (source_id, external_id, title, description,
		                   published_at, audio_url, duration_ms, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'new')
		ON CONFLICT (source_id, external_id) DO NOTHING
		RETURNING id`,
		sourceID, externalID, title, desc, publishedAt, audioURL, durationMS,
	).Scan(&id)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// ON CONFLICT DO NOTHING returned no row: already have it.
			return false, nil
		}
		return false, err
	}
	return true, nil
}
