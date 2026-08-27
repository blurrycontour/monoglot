package ingest

import (
	"context"
	"database/sql"
	"strings"
)

// DefaultModel is what a server transcribes with until somebody chooses
// otherwise. Small is the right default: it beats whisper-large-v3 on Swedish
// and costs a third of medium's memory.
const DefaultModel = "KBLab/kb-whisper-small"

const modelKey = "transcription_model"

// ErrBadModel is a model id that could never name weights. Distinct so the
// HTTP layer can answer 400 rather than 500; whether the weights actually
// exist is the worker's question, not this one's.
type ErrBadModel struct{ Reason string }

func (e ErrBadModel) Error() string { return e.Reason }

// TranscriptionModel returns the model this server transcribes with.
//
// Read per run rather than cached: it is edited from the app, and a cached
// copy would mean the change took effect on the next restart — which is the
// arrangement this replaced.
func TranscriptionModel(ctx context.Context, pool *sql.DB) string {
	var v string
	err := pool.QueryRowContext(ctx,
		`SELECT value FROM settings WHERE key = ?`, modelKey).Scan(&v)
	if err != nil || strings.TrimSpace(v) == "" {
		return DefaultModel
	}
	return v
}

// CheckModelID rejects what could not be a model id at all.
//
// Run before asking the worker: a bare word or a path is answered here, and
// the round trip — which reaches Hugging Face and is the slow half of
// validating — is spent only on ids that might be real.
func CheckModelID(name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return ErrBadModel{"no model given"}
	}
	// A Hugging Face id is owner/name.
	if strings.Count(name, "/") != 1 || strings.HasPrefix(name, "/") ||
		strings.HasSuffix(name, "/") || strings.ContainsAny(name, " \t\\") {
		return ErrBadModel{"expected a Hugging Face model id, like KBLab/kb-whisper-small"}
	}
	return nil
}

// SetTranscriptionModel stores the choice. The caller validates it against the
// worker first: this only re-checks the shape.
func SetTranscriptionModel(ctx context.Context, pool *sql.DB, name string) error {
	name = strings.TrimSpace(name)
	if err := CheckModelID(name); err != nil {
		return err
	}
	_, err := pool.ExecContext(ctx, `
		INSERT INTO settings (key, value) VALUES (?, ?)
		ON CONFLICT (key) DO UPDATE SET
		  value = excluded.value,
		  updated_at = strftime('%Y-%m-%d %H:%M:%S','now')`, modelKey, name)
	return err
}
