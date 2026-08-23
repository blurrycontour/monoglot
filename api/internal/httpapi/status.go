package httpapi

import "net/http"

// PipelineStatus lets the app show what the server is still working on, so a
// fresh install is usable immediately instead of looking empty while the slow
// transcription stage runs.
type PipelineStatus struct {
	Counts     map[string]int `json:"counts"`
	Ready      int            `json:"ready"`
	Processing int            `json:"processing"`
	Failed     int            `json:"failed"`
	Archived   int            `json:"archived"`
	// Sub-counts so the app can show real progress rather than a spinner.
	Queued        int  `json:"queued"` // downloaded, waiting for the worker
	Transcribing  int  `json:"transcribing"`
	Downloading   int  `json:"downloading"` // new or mid-download
	IngestRunning bool `json:"ingest_running"`
}

func (s *Server) pipelineStatus(w http.ResponseWriter, r *http.Request) {
	rows, err := s.pool.Query(r.Context(),
		`SELECT status, count(*) FROM items GROUP BY status`)
	if err != nil {
		serverError(w, err)
		return
	}
	defer rows.Close()

	out := PipelineStatus{Counts: map[string]int{}, IngestRunning: s.runner.Running()}
	for rows.Next() {
		var status string
		var n int
		if err := rows.Scan(&status, &n); err != nil {
			serverError(w, err)
			return
		}
		out.Counts[status] = n
		switch status {
		case "ready":
			out.Ready = n
		case "failed":
			out.Failed = n
		case "archived":
			// Deliberately deferred, not in flight. Counting these as
			// processing made a 175-episode archive read as "preparing 189
			// episodes" forever.
			out.Archived = n
		case "downloaded":
			out.Queued = n
			out.Processing += n
		case "transcribing":
			out.Transcribing = n
			out.Processing += n
		default:
			// new, downloading: fetching audio.
			out.Downloading += n
			out.Processing += n
		}
	}
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}
