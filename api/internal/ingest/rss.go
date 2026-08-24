package ingest

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"html"
	"log"
	"net/http"
	"strings"
	"time"

	"database/sql"

	"github.com/blurrycontour/monoglot/api/internal/db"
)

// 8 Sidor: the article feed at /feed/ carries no audio enclosures, and the
// per-article "Lyssna" button is ReadSpeaker text-to-speech - synthetic,
// JavaScript-gated, and the wrong material for speech-perception training.
// Per the spec's own fallback we ingest the podcast feed instead, which has
// real enclosures of human-read audio, one item per day.
type rssFeed struct {
	Channel struct {
		Title string `xml:"title"`
		Items []struct {
			Title       string `xml:"title"`
			Link        string `xml:"link"`
			GUID        string `xml:"guid"`
			PubDate     string `xml:"pubDate"`
			Description string `xml:"description"`
			Enclosure   struct {
				URL    string `xml:"url,attr"`
				Type   string `xml:"type,attr"`
				Length int64  `xml:"length,attr"`
			} `xml:"enclosure"`
			Duration string `xml:"duration"` // itunes:duration
		} `xml:"item"`
	} `xml:"channel"`
}

func discoverRSS(ctx context.Context, pool *sql.DB, s Source) (int, error) {
	var cfg struct {
		FeedURL string `json:"feed_url"`
	}
	if err := json.Unmarshal(s.Config, &cfg); err != nil {
		return 0, err
	}
	if cfg.FeedURL == "" {
		return 0, fmt.Errorf("source %s has no feed_url in config", s.Slug)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, cfg.FeedURL, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "monoglot/1.0 (personal use)")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("rss %s: %w", cfg.FeedURL, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("rss %s: status %d", cfg.FeedURL, resp.StatusCode)
	}

	var feed rssFeed
	dec := xml.NewDecoder(resp.Body)
	dec.Strict = false
	if err := dec.Decode(&feed); err != nil {
		return 0, fmt.Errorf("rss %s: decode: %w", cfg.FeedURL, err)
	}

	n := 0
	for _, it := range feed.Channel.Items {
		if it.Enclosure.URL == "" {
			continue
		}
		externalID := it.GUID
		if externalID == "" {
			externalID = it.Enclosure.URL
		}

		var pub any
		if t, err := parseRSSTime(it.PubDate); err == nil {
			pub = db.FormatTime(t)
		}
		var durMS any
		if ms := parseDuration(it.Duration); ms > 0 {
			durMS = ms
		}

		inserted, err := insertItem(ctx, pool, s.ID, externalID,
			stripHTML(it.Title), stripHTML(it.Description),
			pub, it.Enclosure.URL, durMS)
		if err != nil {
			return n, err
		}
		if inserted {
			n++
		}
	}
	if len(feed.Channel.Items) == 0 {
		log.Printf("WARNING discover %s: feed %s had 0 items", s.Slug, cfg.FeedURL)
	}
	return n, nil
}

// stripHTML flattens an RSS description to plain text.
//
// Acast feeds put full HTML in <description>, so the app was rendering literal
// <p> and <a href> tags. Block-level tags become spaces so words do not run
// together, then entities are decoded.
func stripHTML(s string) string {
	if !strings.ContainsAny(s, "<&") {
		return strings.TrimSpace(s)
	}

	var b strings.Builder
	depth := 0
	for _, r := range s {
		switch {
		case r == '<':
			depth++
			// A tag boundary is a word boundary.
			b.WriteRune(' ')
		case r == '>':
			if depth > 0 {
				depth--
			}
		case depth == 0:
			b.WriteRune(r)
		}
	}

	out := html.UnescapeString(b.String())
	// Collapse the whitespace the tag stripping introduced.
	return strings.Join(strings.Fields(out), " ")
}

func parseRSSTime(s string) (time.Time, error) {
	s = strings.TrimSpace(s)
	for _, layout := range []string{time.RFC1123Z, time.RFC1123, time.RFC822Z, time.RFC822} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.UTC(), nil
		}
	}
	return time.Time{}, fmt.Errorf("unrecognised pubDate %q", s)
}

// parseDuration handles itunes:duration, which may be "HH:MM:SS", "MM:SS" or
// plain seconds. Returns milliseconds.
func parseDuration(s string) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	parts := strings.Split(s, ":")
	secs := 0
	for _, p := range parts {
		n := 0
		if _, err := fmt.Sscanf(p, "%d", &n); err != nil {
			return 0
		}
		secs = secs*60 + n
	}
	return secs * 1000
}
