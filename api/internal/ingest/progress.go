package ingest

import (
	"io"
	"sync"
	"time"
)

// Progress of the downloads running right now.
//
// Keyed by item, because several run at once: downloading is I/O, and a few
// in parallel finish sooner than the same few one after another. Transcription
// stays serial — that one really does saturate a resource.
//
// The transcription stage reports its own progress from the worker. This is
// the other half: a 19-minute episode is ~19MB, and on a slow connection the
// download is long enough that a bare "downloading" tells you nothing about
// whether it is moving.
type DownloadState struct {
	ItemID   int     `json:"item_id"`
	Written  int64   `json:"written_bytes"`
	Total    int64   `json:"total_bytes"`
	Fraction float64 `json:"fraction"`
	Elapsed  float64 `json:"elapsed_seconds"`
}

type downloadSlot struct {
	written int64
	total   int64
	started time.Time
}

var (
	dlMu sync.RWMutex
	dl   = map[int]*downloadSlot{}
)

// DownloadProgress returns the state of every download in flight, keyed by
// item id. Empty when nothing is being fetched.
func DownloadProgress() map[int]DownloadState {
	dlMu.RLock()
	defer dlMu.RUnlock()
	if len(dl) == 0 {
		return nil
	}
	out := make(map[int]DownloadState, len(dl))
	for id, s := range dl {
		st := DownloadState{
			ItemID:  id,
			Written: s.written,
			Total:   s.total,
			Elapsed: time.Since(s.started).Seconds(),
		}
		// Content-Length is advisory: a server that omits it leaves the
		// fraction at zero, and the app shows an indeterminate bar rather
		// than a wrong one.
		if s.total > 0 {
			st.Fraction = float64(s.written) / float64(s.total)
			if st.Fraction > 1 {
				st.Fraction = 1
			}
		}
		out[id] = st
	}
	return out
}

func startDownload(itemID int, total int64) {
	dlMu.Lock()
	dl[itemID] = &downloadSlot{total: total, started: time.Now()}
	dlMu.Unlock()
}

func endDownload(itemID int) {
	dlMu.Lock()
	delete(dl, itemID)
	dlMu.Unlock()
}

// countingWriter records bytes as they are written, so progress costs one
// locked increment per copy buffer rather than a stat of the partial file.
type countingWriter struct {
	w  io.Writer
	id int
}

func (c countingWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	if n > 0 {
		dlMu.Lock()
		if s := dl[c.id]; s != nil {
			s.written += int64(n)
		}
		dlMu.Unlock()
	}
	return n, err
}
