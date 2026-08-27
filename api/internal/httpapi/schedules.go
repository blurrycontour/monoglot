package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

// getSchedules lists the unattended run times, with the next one resolved.
//
// next_run is computed here rather than on the phone because it depends on the
// server's clock and timezone, which is the only clock the schedule means
// anything against — a phone in another country would draw a confident and
// wrong answer.
func (s *Server) getSchedules(w http.ResponseWriter, r *http.Request) {
	times, err := ingest.ListSchedules(r.Context(), s.pool)
	if err != nil {
		serverError(w, err)
		return
	}
	out := map[string]any{"schedules": times, "next_run": nil}
	if next, ok := s.runner.NextRun(r.Context(), time.Now()); ok {
		out["next_run"] = next.Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) addSchedule(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Hour   int `json:"hour"`
		Minute int `json:"minute"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, "invalid body")
		return
	}
	sched, err := ingest.AddSchedule(r.Context(), s.pool, body.Hour, body.Minute)
	if errors.Is(err, ingest.ErrBadTime) {
		badRequest(w, err.Error())
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	s.runner.ReloadSchedules()
	writeJSON(w, http.StatusOK, sched)
}

func (s *Server) deleteSchedule(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.Atoi(chi.URLParam(r, "id"))
	if err != nil {
		badRequest(w, "invalid id")
		return
	}
	if err := ingest.DeleteSchedule(r.Context(), s.pool, id); err != nil {
		serverError(w, err)
		return
	}
	s.runner.ReloadSchedules()
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}
