package httpapi

import (
	"bytes"
	"net/http"
	"testing"
	"time"
)

// postBody calls the real HTTP surface with a JSON payload, auth included.
func postBody(t *testing.T, r *rig, path, body string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, r.api.URL+path, bytes.NewBufferString(body))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST %s: %v", path, err)
	}
	res.Body.Close()
	return res
}

type listeningResponse struct {
	Days []DayTotal `json:"days"`
}

// The phone flushes what it has buffered, repeatedly, and the day accumulates.
// Replacing rather than adding would mean each flush discarded every minute the
// server already had — which is most of them, since the buffer is emptied as it
// is sent.
func TestListeningAccumulatesAcrossFlushes(t *testing.T) {
	r := newRig(t)
	day := time.Now().Format("2006-01-02")

	postBody(t, r, "/api/listening", `{"days":[{"day":"`+day+`","ms":60000}]}`)
	postBody(t, r, "/api/listening", `{"days":[{"day":"`+day+`","ms":30000}]}`)

	var got listeningResponse
	r.get("/api/listening?days=7", &got)

	if len(got.Days) != 1 {
		t.Fatalf("want one day, got %d: %+v", len(got.Days), got.Days)
	}
	if got.Days[0].Ms != 90000 {
		t.Fatalf("want 90000ms accumulated, got %d", got.Days[0].Ms)
	}
}

// A batch spans midnight, or several days of listening with no server in reach.
func TestListeningAcceptsABatchOfDays(t *testing.T) {
	r := newRig(t)
	today := time.Now()
	d1 := today.AddDate(0, 0, -1).Format("2006-01-02")
	d2 := today.Format("2006-01-02")

	postBody(t, r, "/api/listening",
		`{"days":[{"day":"`+d1+`","ms":1000},{"day":"`+d2+`","ms":2000}]}`)

	var got listeningResponse
	r.get("/api/listening?days=7", &got)

	if len(got.Days) != 2 {
		t.Fatalf("want two days, got %d: %+v", len(got.Days), got.Days)
	}
	// Ordered by day, so a chart can render straight from the response.
	if got.Days[0].Day != d1 || got.Days[1].Day != d2 {
		t.Fatalf("want %s then %s, got %+v", d1, d2, got.Days)
	}
}

// Days outside the window are not the client's business: it asks for what it
// intends to draw.
func TestListeningWindowExcludesOlderDays(t *testing.T) {
	r := newRig(t)
	old := time.Now().AddDate(0, 0, -40).Format("2006-01-02")
	postBody(t, r, "/api/listening", `{"days":[{"day":"`+old+`","ms":5000}]}`)

	var got listeningResponse
	r.get("/api/listening?days=7", &got)
	if len(got.Days) != 0 {
		t.Fatalf("want nothing inside a 7 day window, got %+v", got.Days)
	}

	r.get("/api/listening?days=90", &got)
	if len(got.Days) != 1 {
		t.Fatalf("want the day inside a 90 day window, got %+v", got.Days)
	}
}

// Nonsense is skipped rather than stored: a zero adds nothing and a malformed
// date would sort into the middle of the chart.
func TestListeningRejectsJunkDays(t *testing.T) {
	r := newRig(t)
	day := time.Now().Format("2006-01-02")
	postBody(t, r, "/api/listening",
		`{"days":[{"day":"nope","ms":1000},{"day":"`+day+`","ms":0},{"day":"`+day+`","ms":500}]}`)

	var got listeningResponse
	r.get("/api/listening?days=7", &got)
	if len(got.Days) != 1 || got.Days[0].Ms != 500 {
		t.Fatalf("want only the valid 500ms row, got %+v", got.Days)
	}
}
