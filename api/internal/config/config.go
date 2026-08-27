package config

import (
	"os"
	"strconv"
)

// Config is the whole of the API's configuration. Defaults here are the same
// values .env.example ships, so the binary still runs sensibly outside compose
// — but .env is the list to change, not this file.
type Config struct {
	DatabasePath string
	Port         string
	AuthToken    string
	AudioDir     string
	RawDir       string
	WorkerURL    string
	APKPath      string

	IngestOnStart bool
}

func Load() Config {
	return Config{
		DatabasePath:  env("DATABASE_PATH", "/data/monoglot.db"),
		Port:          env("API_PORT", "8080"),
		AuthToken:     env("AUTH_TOKEN", ""),
		AudioDir:      env("AUDIO_DIR", "/data/audio"),
		RawDir:        env("RAW_DIR", "/data/raw"),
		WorkerURL:     env("WORKER_URL", "http://worker:9000"),
		APKPath:       env("APK_PATH", "/data/apk/monoglot.apk"),
		IngestOnStart: envBool("INGEST_ON_START", false),
	}
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envBool(k string, def bool) bool {
	if v := os.Getenv(k); v != "" {
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return def
}
