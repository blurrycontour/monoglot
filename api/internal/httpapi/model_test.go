package httpapi

import (
	"context"
	"net/http"
	"testing"

	"github.com/blurrycontour/monoglot/api/internal/ingest"
)

func TestFreshServerUsesTheDefaultModel(t *testing.T) {
	r := newRig(t)

	var got struct {
		Model     string              `json:"model"`
		Default   string              `json:"default"`
		Suggested []map[string]string `json:"suggested"`
	}
	r.get("/api/model", &got)
	if got.Model != ingest.DefaultModel {
		t.Fatalf("model = %q, want %q", got.Model, ingest.DefaultModel)
	}
	if len(got.Suggested) == 0 {
		t.Error("no suggestions offered: the id has to be typed from memory")
	}
}

func TestModelIsValidatedBeforeItIsStored(t *testing.T) {
	r := newRig(t)
	r.worker.rejectModel = "KBLab/not-a-real-model"

	res := postBody(t, r, "/api/model", `{"model":"KBLab/not-a-real-model"}`)
	if res.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", res.StatusCode)
	}

	// The point of validating first: a rejected id must not become the model
	// the pipeline tries to use at 03:30.
	var got struct {
		Model string `json:"model"`
	}
	r.get("/api/model", &got)
	if got.Model != ingest.DefaultModel {
		t.Fatalf("model = %q, want the default to stand", got.Model)
	}
}

// Nonsense that is not a model id at all is refused without troubling the
// worker, which is the slow half of validating.
func TestModelRejectsWhatIsNotAnId(t *testing.T) {
	r := newRig(t)
	for _, bad := range []string{"", "kbwhisper", "/leading", "trailing/", "a/b/c", "has space/x"} {
		res := postBody(t, r, "/api/model", `{"model":"`+bad+`"}`)
		if res.StatusCode != http.StatusBadRequest {
			t.Errorf("%q: status = %d, want 400", bad, res.StatusCode)
		}
	}
}

func TestChosenModelReachesTheWorker(t *testing.T) {
	r := newRig(t)

	res := postBody(t, r, "/api/model", `{"model":"KBLab/kb-whisper-medium"}`)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", res.StatusCode)
	}

	var got struct {
		Model string `json:"model"`
	}
	r.get("/api/model", &got)
	if got.Model != "KBLab/kb-whisper-medium" {
		t.Fatalf("model = %q, not stored", got.Model)
	}

	// And it is sent with the transcription, not read from the worker's own
	// environment — that is what makes the change take effect without a
	// restart of the worker container.
	id := r.addItem("downloaded")
	r.srv.runner.Run(context.Background(), "test")
	if r.status(id) != "ready" {
		t.Fatalf("item %d = %q, want ready", id, r.status(id))
	}
	r.worker.mu.Lock()
	seen := append([]string(nil), r.worker.models...)
	r.worker.mu.Unlock()
	if len(seen) == 0 || seen[len(seen)-1] != "KBLab/kb-whisper-medium" {
		t.Fatalf("worker saw models %v, want the chosen one last", seen)
	}
}
