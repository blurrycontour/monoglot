package ingest

import (
	"context"
	"database/sql"
)

// Schedule is one time of day at which the pipeline runs unattended.
//
// Local time, deliberately: the times are typed against the same wall clock
// the container runs on, and storing UTC would shift every one of them twice
// a year for the sake of a precision nothing here needs.
type Schedule struct {
	ID     int `json:"id"`
	Hour   int `json:"hour"`
	Minute int `json:"minute"`
}

func ListSchedules(ctx context.Context, pool *sql.DB) ([]Schedule, error) {
	rows, err := pool.QueryContext(ctx,
		`SELECT id, hour, minute FROM schedules ORDER BY hour, minute`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Schedule{}
	for rows.Next() {
		var s Schedule
		if err := rows.Scan(&s.ID, &s.Hour, &s.Minute); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// AddSchedule is idempotent: adding a time that already exists returns the
// existing row rather than failing. A double tap on the confirm button of a
// time picker is not an error worth reporting.
func AddSchedule(ctx context.Context, pool *sql.DB, hour, minute int) (Schedule, error) {
	if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
		return Schedule{}, ErrBadTime
	}
	if _, err := pool.ExecContext(ctx,
		`INSERT OR IGNORE INTO schedules (hour, minute) VALUES (?, ?)`,
		hour, minute); err != nil {
		return Schedule{}, err
	}
	s := Schedule{Hour: hour, Minute: minute}
	err := pool.QueryRowContext(ctx,
		`SELECT id FROM schedules WHERE hour = ? AND minute = ?`,
		hour, minute).Scan(&s.ID)
	return s, err
}

func DeleteSchedule(ctx context.Context, pool *sql.DB, id int) error {
	_, err := pool.ExecContext(ctx, `DELETE FROM schedules WHERE id = ?`, id)
	return err
}

// ErrBadTime is returned for a time that is not a time. Its own value so the
// HTTP layer can answer 400 rather than 500.
var ErrBadTime = errBadTime{}

type errBadTime struct{}

func (errBadTime) Error() string { return "hour must be 0-23 and minute 0-59" }
