package httpapi

import (
	"net/http"
	"strconv"
	"testing"

	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

type schedulesResponse struct {
	Schedules []ingest.Schedule `json:"schedules"`
	NextRun   *string           `json:"next_run"`
}

// The whole point of moving the schedule out of .env: a server nobody has
// configured must not ingest on a timetable it invented.
func TestFreshServerHasNoSchedule(t *testing.T) {
	r := newRig(t)

	var got schedulesResponse
	r.get("/api/schedules", &got)

	if len(got.Schedules) != 0 {
		t.Fatalf("want no schedules on a fresh server, got %+v", got.Schedules)
	}
	if got.NextRun != nil {
		t.Fatalf("want no next run, got %q", *got.NextRun)
	}
}

func TestScheduleAddListDelete(t *testing.T) {
	r := newRig(t)

	postBody(t, r, "/api/schedules", `{"hour":3,"minute":30}`)
	postBody(t, r, "/api/schedules", `{"hour":14,"minute":0}`)

	var got schedulesResponse
	r.get("/api/schedules", &got)

	if len(got.Schedules) != 2 {
		t.Fatalf("want two schedules, got %+v", got.Schedules)
	}
	// Ordered by time of day, so the list reads like a timetable.
	if got.Schedules[0].Hour != 3 || got.Schedules[1].Hour != 14 {
		t.Fatalf("want 03:30 then 14:00, got %+v", got.Schedules)
	}
	if got.NextRun == nil {
		t.Fatal("want a next run once a schedule exists")
	}

	req, _ := http.NewRequest(http.MethodDelete,
		r.api.URL+"/api/schedules/"+strconv.Itoa(got.Schedules[0].ID), nil)
	req.Header.Set("Authorization", "Bearer test-token")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("delete: %v", err)
	}
	res.Body.Close()

	r.get("/api/schedules", &got)
	if len(got.Schedules) != 1 || got.Schedules[0].Hour != 14 {
		t.Fatalf("want only 14:00 left, got %+v", got.Schedules)
	}
}

// A time picker confirmed twice is not an error, and must not produce two
// rows that would run the pipeline twice at the same minute.
func TestScheduleAddIsIdempotent(t *testing.T) {
	r := newRig(t)

	postBody(t, r, "/api/schedules", `{"hour":6,"minute":15}`)
	res := postBody(t, r, "/api/schedules", `{"hour":6,"minute":15}`)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("want 200 on a repeat add, got %d", res.StatusCode)
	}

	var got schedulesResponse
	r.get("/api/schedules", &got)
	if len(got.Schedules) != 1 {
		t.Fatalf("want one schedule, got %+v", got.Schedules)
	}
}

func TestScheduleRejectsImpossibleTime(t *testing.T) {
	for _, body := range []string{`{"hour":24,"minute":0}`, `{"hour":3,"minute":60}`, `{"hour":-1,"minute":0}`} {
		r := newRig(t)
		res := postBody(t, r, "/api/schedules", body)
		if res.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s: want 400, got %d", body, res.StatusCode)
		}
	}
}
