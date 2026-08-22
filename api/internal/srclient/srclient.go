// Package srclient talks to the Sveriges Radio Open API.
//
// The API is officially unmaintained but functional. Every call here assumes it
// may misbehave: short timeouts, defensive parsing, and errors that name the SR
// API explicitly so a nightly failure is obvious in the log rather than silent.
package srclient

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

const BaseURL = "https://api.sr.se/api/v2"

type Client struct {
	HTTP *http.Client
}

func New() *Client {
	return &Client{HTTP: &http.Client{Timeout: 30 * time.Second}}
}

type Program struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type PodFile struct {
	URL      string `json:"url"`
	Duration int    `json:"duration"` // seconds
	Size     int64  `json:"filesizeinbytes"`
}

type Episode struct {
	ID             int      `json:"id"`
	Title          string   `json:"title"`
	Description    string   `json:"description"`
	URL            string   `json:"url"`
	PublishDateUTC srTime   `json:"publishdateutc"`
	ListenPodfile  *PodFile `json:"listenpodfile"`
	DownloadPodile *PodFile `json:"downloadpodfile"`
}

// Audio picks the best available audio file. downloadpodfile is the full
// episode; listenpodfile is what the web player streams. In practice for
// Klartext they are the same file, but prefer the download variant.
func (e Episode) Audio() *PodFile {
	if e.DownloadPodile != nil && e.DownloadPodile.URL != "" {
		return e.DownloadPodile
	}
	if e.ListenPodfile != nil && e.ListenPodfile.URL != "" {
		return e.ListenPodfile
	}
	return nil
}

// srTime handles SR's legacy Microsoft-style JSON date, "/Date(1787338500000)/".
type srTime struct{ time.Time }

var srDateRe = regexp.MustCompile(`/Date\((-?\d+)`)

func (t *srTime) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return nil // absent or unexpected shape: leave zero rather than fail
	}
	m := srDateRe.FindStringSubmatch(s)
	if m == nil {
		return nil
	}
	ms, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return nil
	}
	t.Time = time.UnixMilli(ms).UTC()
	return nil
}

func (c *Client) get(ctx context.Context, path string, q url.Values, out any) error {
	q.Set("format", "json")
	u := BaseURL + path + "?" + q.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("sr api %s: %w", path, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("sr api %s: status %d", path, resp.StatusCode)
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return fmt.Errorf("sr api %s: decode: %w", path, err)
	}
	return nil
}

// FindProgram locates a program by (case-insensitive substring) name. Used by
// the bootstrap check so a renumbered program id surfaces as a clear error
// rather than an empty feed.
func (c *Client) FindProgram(ctx context.Context, name string) ([]Program, error) {
	var out struct {
		Programs []Program `json:"programs"`
	}
	q := url.Values{}
	q.Set("pagination", "false")
	if err := c.get(ctx, "/programs/index", q, &out); err != nil {
		return nil, err
	}
	var hits []Program
	lname := normalize(name)
	for _, p := range out.Programs {
		if contains(normalize(p.Name), lname) {
			hits = append(hits, p)
		}
	}
	return hits, nil
}

func (c *Client) Episodes(ctx context.Context, programID, size int) ([]Episode, error) {
	var out struct {
		Episodes []Episode `json:"episodes"`
	}
	q := url.Values{}
	q.Set("programid", strconv.Itoa(programID))
	q.Set("size", strconv.Itoa(size))
	if err := c.get(ctx, "/episodes/index", q, &out); err != nil {
		return nil, err
	}
	return out.Episodes, nil
}

func normalize(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r >= 'A' && r <= 'Z' {
			r += 'a' - 'A'
		}
		out = append(out, r)
	}
	return string(out)
}

func contains(hay, needle string) bool {
	if needle == "" {
		return true
	}
	for i := 0; i+len(needle) <= len(hay); i++ {
		if hay[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
