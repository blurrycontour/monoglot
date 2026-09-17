package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

// ModelStorageEntry is one Whisper model downloaded into the worker's cache.
type ModelStorageEntry struct {
	Name   string `json:"name"`
	Bytes  int64  `json:"bytes"`
	Active bool   `json:"active"`
}

// listModels proxies the worker's cache listing, marking whichever one is the
// currently configured transcription model so the app can stop it being
// deleted out from under the pipeline.
func (s *Server) listModels(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	current := ingest.TranscriptionModel(ctx, s.pool)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimRight(s.cfg.WorkerURL, "/")+"/models", nil)
	if err != nil {
		serverError(w, err)
		return
	}
	client := &http.Client{Timeout: 5 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"models": []ModelStorageEntry{}})
		return
	}
	defer res.Body.Close()

	var out struct {
		Models []struct {
			Name  string `json:"name"`
			Bytes int64  `json:"bytes"`
		} `json:"models"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		serverError(w, err)
		return
	}

	entries := make([]ModelStorageEntry, 0, len(out.Models))
	for _, m := range out.Models {
		entries = append(entries, ModelStorageEntry{
			Name: m.Name, Bytes: m.Bytes, Active: m.Name == current,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"models": entries})
}

// deleteModel removes one downloaded model's weights from the worker's cache.
// The currently configured model is refused rather than deleted: the pipeline
// would otherwise re-download it mid-run the next time it is needed.
func (s *Server) deleteModel(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "*")
	if strings.TrimSpace(name) == "" {
		badRequest(w, "no model given")
		return
	}
	if name == ingest.TranscriptionModel(r.Context(), s.pool) {
		badRequest(w, "cannot delete the active transcription model")
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodDelete,
		strings.TrimRight(s.cfg.WorkerURL, "/")+"/models/"+name, nil)
	if err != nil {
		serverError(w, err)
		return
	}
	client := &http.Client{Timeout: 10 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cannot reach the transcription worker"})
		return
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		var out struct {
			Detail string `json:"detail"`
		}
		json.NewDecoder(res.Body).Decode(&out)
		if out.Detail == "" {
			out.Detail = "the worker rejected this delete"
		}
		writeJSON(w, res.StatusCode, map[string]string{"error": out.Detail})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": name})
}
