// Package httpapi is the read/write surface consumed by the Android client.
package httpapi

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adityasingh/svenska/api/internal/config"
	"github.com/adityasingh/svenska/api/internal/ingest"
)

type Server struct {
	pool   *pgxpool.Pool
	cfg    config.Config
	runner *ingest.Runner
}

func NewServer(pool *pgxpool.Pool, cfg config.Config, runner *ingest.Runner) *Server {
	return &Server{pool: pool, cfg: cfg, runner: runner}
}

func (s *Server) Routes() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)
	r.Use(logRequests)

	// Unauthenticated: used by the Docker healthcheck.
	r.Get("/api/health", s.health)

	// Unauthenticated install page. The phone needs the APK before it can
	// hold a token, so this cannot sit behind auth. Serves only the APK.
	r.Get("/", s.downloadPage)
	r.Get("/download", s.downloadPage)
	r.Get("/download/svenska.apk", s.downloadAPK)

	r.Group(func(r chi.Router) {
		r.Use(s.auth)

		r.Get("/api/items", s.listItems)
		r.Get("/api/items/{id}", s.getItem)
		r.Get("/api/items/{id}/bundle", s.getBundle)
		r.Get("/api/items/{id}/audio", s.getAudio)
		r.Post("/api/items/{id}/progress", s.postProgress)

		r.Get("/api/lookup", s.lookup)
		r.Post("/api/words/{lemma}/status", s.postWordStatus)
		r.Get("/api/words", s.listWords)
		r.Get("/api/export/anki", s.exportAnki)

		r.Get("/api/status", s.pipelineStatus)
		r.Get("/api/system", s.systemInfo)
		r.Get("/api/languages", s.listLanguages)
		r.Post("/api/items/{id}/archive", s.archiveItem)
		r.Post("/api/items/{id}/restore", s.restoreItem)
		r.Post("/api/admin/cleanup", s.cleanup)
		r.Get("/api/sources", s.listSources)
		r.Post("/api/sources/{id}/enabled", s.setSourceEnabled)
		r.Post("/api/admin/ingest", s.triggerIngest)
	})
	return r
}

// auth checks a single static bearer token. Single user; nothing more is
// warranted, and this service must not be exposed publicly.
func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.AuthToken == "" {
			http.Error(w, "server has no AUTH_TOKEN configured", http.StatusInternalServerError)
			return
		}
		got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if got == "" {
			// Media players cannot always set headers; allow a token query
			// param so the audio URL is usable directly by ExoPlayer.
			got = r.URL.Query().Get("token")
		}
		if subtleCompare(got, s.cfg.AuthToken) {
			next.ServeHTTP(w, r)
			return
		}
		http.Error(w, "unauthorized", http.StatusUnauthorized)
	})
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		d := time.Since(start)
		// The spec wants the tap-to-define path measured: anything over 100ms
		// there is a bug worth fixing, so make it impossible to miss.
		if strings.HasPrefix(r.URL.Path, "/api/lookup") && d > 100*time.Millisecond {
			log.Printf("SLOW %s %s %s (>100ms budget)", r.Method, r.URL.Path, d)
		} else {
			log.Printf("%s %s %s", r.Method, r.URL.Path, d.Round(time.Millisecond))
		}
	})
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	out := map[string]any{"status": "ok", "time": time.Now().UTC()}
	if err := s.pool.Ping(ctx); err != nil {
		out["status"] = "degraded"
		out["db"] = err.Error()
		writeJSON(w, http.StatusServiceUnavailable, out)
		return
	}
	out["db"] = "ok"
	out["ingest_running"] = s.runner.Running()
	writeJSON(w, http.StatusOK, out)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("ERROR encoding response: %v", err)
	}
}

func badRequest(w http.ResponseWriter, msg string) {
	writeJSON(w, http.StatusBadRequest, map[string]string{"error": msg})
}

func serverError(w http.ResponseWriter, err error) {
	log.Printf("ERROR: %v", err)
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
}

func subtleCompare(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

func intParam(r *http.Request, name string) (int, error) {
	return strconv.Atoi(chi.URLParam(r, name))
}

func queryInt(r *http.Request, name string, def int) int {
	if v := r.URL.Query().Get(name); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
