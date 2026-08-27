package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

// Models the app offers as a starting point. Not a whitelist — anything the
// worker can load is allowed — but a typed model id is a poor way to discover
// that KBLab publishes five sizes, and these are the ones worth choosing
// between on a homelab box.
var suggestedModels = []map[string]string{
	{"id": "KBLab/kb-whisper-tiny", "note": "fastest, roughly 60 MB in memory"},
	{"id": "KBLab/kb-whisper-base", "note": "roughly 110 MB"},
	{"id": "KBLab/kb-whisper-small", "note": "the default — beats whisper-large-v3 on Swedish, ~330 MB"},
	{"id": "KBLab/kb-whisper-medium", "note": "better, ~3x slower, ~1 GB and a 1.5 GB download"},
	{"id": "KBLab/kb-whisper-large", "note": "best, only worth it with plenty of RAM and time"},
}

func (s *Server) getModel(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"model":     ingest.TranscriptionModel(r.Context(), s.pool),
		"default":   ingest.DefaultModel,
		"suggested": suggestedModels,
	})
}

// setModel validates against the worker before storing. Storing first and
// finding out at 03:30 that the id was mistyped would cost a night's ingest,
// and the failure would surface as a stalled pipeline rather than as an
// answer to the thing the user just did.
func (s *Server) setModel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Model string `json:"model"`
	}
	if err := decodeJSON(r, &body); err != nil {
		badRequest(w, err.Error())
		return
	}

	name := strings.TrimSpace(body.Model)
	// Shape first, then the worker: nonsense is answered without a round trip
	// to Hugging Face.
	if err := ingest.CheckModelID(name); err != nil {
		badRequest(w, err.Error())
		return
	}
	if err := s.validateModel(r, name); err != nil {
		badRequest(w, err.Error())
		return
	}

	if err := ingest.SetTranscriptionModel(r.Context(), s.pool, name); err != nil {
		var bad ingest.ErrBadModel
		if errors.As(err, &bad) {
			badRequest(w, bad.Error())
			return
		}
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"model": name})
}

// validateModel asks the worker whether it could load this. The worker checks
// the repository listing rather than downloading, so this is a round trip and
// not a wait.
func (s *Server) validateModel(r *http.Request, name string) error {
	if strings.TrimSpace(name) == "" {
		return errors.New("no model given")
	}
	body, _ := json.Marshal(map[string]string{"model": name})
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost,
		strings.TrimRight(s.cfg.WorkerURL, "/")+"/validate", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return errors.New("cannot reach the transcription worker to check this model")
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusOK {
		return nil
	}
	var out struct {
		Detail string `json:"detail"`
	}
	json.NewDecoder(res.Body).Decode(&out)
	if out.Detail == "" {
		out.Detail = "the worker rejected this model"
	}
	return errors.New(out.Detail)
}
