package srclient

import (
	"encoding/json"
	"testing"
	"time"
)

// SR serves legacy Microsoft-style JSON dates. A parse failure must degrade to
// a zero time rather than failing the whole feed.
func TestSRTimeUnmarshal(t *testing.T) {
	var v struct {
		D srTime `json:"d"`
	}
	if err := json.Unmarshal([]byte(`{"d":"/Date(1787338500000)/"}`), &v); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	want := time.UnixMilli(1787338500000).UTC()
	if !v.D.Time.Equal(want) {
		t.Errorf("got %v, want %v", v.D.Time, want)
	}

	for _, bad := range []string{`{"d":"garbage"}`, `{"d":null}`, `{"d":123}`} {
		var z struct {
			D srTime `json:"d"`
		}
		if err := json.Unmarshal([]byte(bad), &z); err != nil {
			t.Errorf("unmarshal(%s) returned error %v, want graceful zero", bad, err)
		}
		if !z.D.IsZero() {
			t.Errorf("unmarshal(%s) = %v, want zero", bad, z.D.Time)
		}
	}
}

func TestEpisodeAudioPrefersDownload(t *testing.T) {
	e := Episode{
		ListenPodfile:  &PodFile{URL: "listen.mp3"},
		DownloadPodile: &PodFile{URL: "download.mp3"},
	}
	if got := e.Audio().URL; got != "download.mp3" {
		t.Errorf("Audio() = %q, want download.mp3", got)
	}
	e.DownloadPodile = nil
	if got := e.Audio().URL; got != "listen.mp3" {
		t.Errorf("Audio() = %q, want listen.mp3", got)
	}
	e.ListenPodfile = nil
	if e.Audio() != nil {
		t.Error("Audio() should be nil when no pod file is present")
	}
}
