package httpapi

import (
	"fmt"
	"html/template"
	"net/http"
	"os"
	"path/filepath"
)

// The download page is deliberately unauthenticated: it exists so the phone
// can fetch the APK from a browser before the app (and therefore the token)
// exists anywhere on the device. It exposes only the APK, never content.
// This service is not to be exposed to the public internet regardless.
func (s *Server) downloadPage(w http.ResponseWriter, r *http.Request) {
	info, err := os.Stat(s.cfg.APKPath)

	data := struct {
		Available bool
		Size      string
		Built     string
		Version   string
		Host      string
	}{
		Host: r.Host,
	}
	if err == nil {
		data.Available = true
		data.Size = fmt.Sprintf("%.1f MB", float64(info.Size())/(1<<20))
		data.Built = info.ModTime().Format("2 Jan 2006, 15:04")
		data.Version = apkVersion(s.cfg.APKPath)
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := downloadTmpl.Execute(w, data); err != nil {
		serverError(w, err)
	}
}

// apkVersion reads the version stamp the build script writes next to the APK.
func apkVersion(apkPath string) string {
	b, err := os.ReadFile(filepath.Join(filepath.Dir(apkPath), "version.txt"))
	if err != nil {
		return ""
	}
	return string(trimSpace(b))
}

func trimSpace(b []byte) []byte {
	for len(b) > 0 && (b[len(b)-1] == '\n' || b[len(b)-1] == '\r' || b[len(b)-1] == ' ') {
		b = b[:len(b)-1]
	}
	return b
}

func (s *Server) downloadAPK(w http.ResponseWriter, r *http.Request) {
	info, err := os.Stat(s.cfg.APKPath)
	if err != nil {
		http.Error(w, "no APK has been built yet", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="svenska.apk"`)
	// Never let a stale APK be served from cache: the whole point of this page
	// is getting the newest build onto the phone.
	w.Header().Set("Cache-Control", "no-store, must-revalidate")
	http.ServeContent(w, r, "svenska.apk", info.ModTime(), mustOpen(w, s.cfg.APKPath))
}

func mustOpen(w http.ResponseWriter, path string) *os.File {
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "cannot read APK", http.StatusInternalServerError)
		return nil
	}
	return f
}

var downloadTmpl = template.Must(template.New("download").Parse(`<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Svenska — install</title>
<style>
  :root { color-scheme: light dark; --fg:#1c1917; --muted:#57534e; --bg:#fafaf9;
          --card:#fff; --accent:#2563eb; --line:#e7e5e4; }
  @media (prefers-color-scheme: dark) {
    :root { --fg:#f5f5f4; --muted:#a8a29e; --bg:#0c0a09; --card:#1c1917;
            --accent:#60a5fa; --line:#292524; }
  }
  * { box-sizing: border-box; }
  body { margin:0; padding:32px 20px; background:var(--bg); color:var(--fg);
         font:16px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
         display:flex; justify-content:center; }
  main { width:100%; max-width:26rem; }
  h1 { font-size:1.5rem; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 24px; font-size:.95rem; }
  .card { background:var(--card); border:1px solid var(--line);
          border-radius:14px; padding:20px; }
  .btn { display:block; text-align:center; text-decoration:none;
         background:var(--accent); color:#fff; font-weight:600; font-size:1.05rem;
         padding:16px; border-radius:11px; margin-bottom:14px; }
  dl { display:grid; grid-template-columns:auto 1fr; gap:6px 16px;
       margin:0; font-size:.9rem; }
  dt { color:var(--muted); }
  dd { margin:0; }
  ol { color:var(--muted); font-size:.9rem; padding-left:20px; margin:22px 0 0; }
  li { margin-bottom:7px; }
  code { background:var(--line); padding:1px 5px; border-radius:4px;
         font-size:.85em; word-break:break-all; }
  .none { color:var(--muted); text-align:center; padding:14px 0; }
</style>
</head><body><main>
  <h1>Svenska</h1>
  <p class="sub">Listening trainer — Android app</p>
  <div class="card">
    {{if .Available}}
      <a class="btn" href="/download/svenska.apk">Download APK</a>
      <dl>
        {{if .Version}}<dt>Version</dt><dd>{{.Version}}</dd>{{end}}
        <dt>Size</dt><dd>{{.Size}}</dd>
        <dt>Built</dt><dd>{{.Built}}</dd>
      </dl>
    {{else}}
      <p class="none">No APK built yet.<br>Run <code>./scripts/android.sh</code> on the server.</p>
    {{end}}
  </div>
  <ol>
    <li>Tap Download. Allow installs from your browser if Android asks.</li>
    <li>Open the app and go to Settings.</li>
    <li>Server URL: <code>http://{{.Host}}</code></li>
    <li>Paste the auth token from the server's <code>.env</code>.</li>
  </ol>
</main></body></html>`))
