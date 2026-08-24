package ingest

import (
	"io"
	"sync"
	"time"
)

// Progress of the download running right now.
//
// One slot, not a map: DownloadPending works through its queue serially, for
// the same reason the worker transcribes one file at a time — these are the
// two stages that saturate a resource, and running them in parallel would only
// make each slower.
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

var (
	dlMu      sync.RWMutex
	dlItem    int
	dlWritten int64
	dlTotal   int64
	dlStarted time.Time
)

// DownloadProgress returns the state of the current download, or false when
// nothing is being fetched.
func DownloadProgress() (DownloadState, bool) {
	dlMu.RLock()
	defer dlMu.RUnlock()
	if dlItem == 0 {
		return DownloadState{}, false
	}
	s := DownloadState{
		ItemID:  dlItem,
		Written: dlWritten,
		Total:   dlTotal,
		Elapsed: time.Since(dlStarted).Seconds(),
	}
	// Content-Length is advisory: a server that omits it leaves the fraction
	// at zero, and the app shows an indeterminate bar rather than a wrong one.
	if dlTotal > 0 {
		s.Fraction = float64(dlWritten) / float64(dlTotal)
		if s.Fraction > 1 {
			s.Fraction = 1
		}
	}
	return s, true
}

func startDownload(itemID int, total int64) {
	dlMu.Lock()
	dlItem, dlWritten, dlTotal, dlStarted = itemID, 0, total, time.Now()
	dlMu.Unlock()
}

func endDownload() {
	dlMu.Lock()
	dlItem, dlWritten, dlTotal = 0, 0, 0
	dlMu.Unlock()
}

// countingWriter records bytes as they are written, so progress costs one
// atomic-ish update per copy buffer rather than a stat of the partial file.
type countingWriter struct {
	w io.Writer
}

func (c countingWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	if n > 0 {
		dlMu.Lock()
		dlWritten += int64(n)
		dlMu.Unlock()
	}
	return n, err
}
