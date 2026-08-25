package httpapi

import (
	"net/http"
	"strconv"
	"time"
)

// DayTotal is one day's listening, in milliseconds.
type DayTotal struct {
	Day string `json:"day"`
	Ms  int64  `json:"ms"`
}

// getListening returns daily totals, most recent last.
//
// Days with no listening are absent rather than zero: the client draws a
// calendar and a bar chart, both of which know their own date range, and
// padding it here would mean agreeing on a timezone that only the phone knows.
func (s *Server) getListening(w http.ResponseWriter, r *http.Request) {
	days := 90
	if v := r.URL.Query().Get("days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 400 {
			days = n
		}
	}
	since := time.Now().AddDate(0, 0, -days).Format("2006-01-02")

	rows, err := s.pool.QueryContext(r.Context(),
		`SELECT day, ms FROM listening WHERE day >= ? ORDER BY day`, since)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := []DayTotal{}
	for rows.Next() {
		var d DayTotal
		if err := rows.Scan(&d.Day, &d.Ms); err != nil {
			serverError(w, err)
			return
		}
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"days": out})
}

// postListening adds time to one or more days.
//
// Additive, and sent as a batch: the phone buffers while it plays and flushes
// what it has, which may span midnight or several days offline. Adding rather
// than setting is what makes a resend harmless to get wrong in the safe
// direction — a dropped flush loses those minutes, where a replaced total
// would lose every minute the phone did not know about.
func (s *Server) postListening(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Days []DayTotal `json:"days"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}

	tx, err := s.pool.BeginTx(r.Context(), nil)
	if err != nil {
		serverError(w, err)
		return
	}
	defer tx.Rollback()

	for _, d := range body.Days {
		if d.Ms <= 0 || len(d.Day) != 10 {
			continue
		}
		if _, err := tx.ExecContext(r.Context(), `
			INSERT INTO listening (day, ms) VALUES (?, ?)
			ON CONFLICT (day) DO UPDATE SET
			  ms = listening.ms + excluded.ms,
			  updated_at = strftime('%Y-%m-%d %H:%M:%S','now')`,
			d.Day, d.Ms); err != nil {
			serverError(w, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
