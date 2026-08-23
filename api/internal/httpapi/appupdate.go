package httpapi

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
)

// AppVersion describes the build currently published on the server, so the
// installed app can tell whether it is behind without the user visiting the
// download page.
type AppVersion struct {
	VersionCode int    `json:"version_code"`
	VersionName string `json:"version_name"`
	SizeBytes   int64  `json:"size_bytes"`
	BuiltAt     string `json:"built_at"`
	DownloadURL string `json:"download_url"`
	Available   bool   `json:"available"`
}

// appVersion is deliberately unauthenticated, like the download page: the app
// checks it before the user has necessarily configured a token, and it leaks
// nothing beyond a build number.
func (s *Server) appVersion(w http.ResponseWriter, r *http.Request) {
	out := AppVersion{DownloadURL: "/download/svenska.apk"}

	manifest := filepath.Join(filepath.Dir(s.cfg.APKPath), "version.json")
	if b, err := os.ReadFile(manifest); err == nil {
		json.Unmarshal(b, &out)
	}
	if fi, err := os.Stat(s.cfg.APKPath); err == nil {
		out.Available = true
		if out.SizeBytes == 0 {
			out.SizeBytes = fi.Size()
		}
	}
	// Re-assert after unmarshal, which would otherwise overwrite it with the
	// manifest's (absent) value.
	out.DownloadURL = "/download/svenska.apk"
	writeJSON(w, http.StatusOK, out)
}
