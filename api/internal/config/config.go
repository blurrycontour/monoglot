package config

import (
	"os"
	"strconv"
)

type Config struct {
	DatabasePath string
	Port         string
	AuthToken    string
	AudioDir     string
	RawDir       string
	WorkerURL    string
	APKPath      string

	IngestHour    int
	IngestMinute  int
	IngestOnStart bool

	LLMEnabled  bool
	OllamaURL   string
	OllamaModel string
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
		IngestHour:    envInt("INGEST_CRON_HOUR", 3),
		IngestMinute:  envInt("INGEST_CRON_MINUTE", 30),
		IngestOnStart: envBool("INGEST_ON_START", false),
		LLMEnabled:    envBool("LLM_ENABLED", false),
		OllamaURL:     env("OLLAMA_URL", "http://host.docker.internal:11434"),
		OllamaModel:   env("OLLAMA_MODEL", "gemma3:12b"),
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
