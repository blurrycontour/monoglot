-- Monoglot schema, SQLite.
--
-- Single user, so no user_id columns anywhere. Timestamps are TEXT in
-- 'YYYY-MM-DD HH:MM:SS' UTC so SQLite's own date functions parse them.
-- JSON columns are TEXT holding a JSON document, read with json_extract.

CREATE TABLE languages (
  code        TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  native_name TEXT,
  asr_code    TEXT NOT NULL,
  enabled     INTEGER NOT NULL DEFAULT 1
);

INSERT INTO languages (code, name, native_name, asr_code)
VALUES ('sv', 'Swedish', 'Svenska', 'sv');

CREATE TABLE sources (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  slug                TEXT UNIQUE NOT NULL,
  name                TEXT NOT NULL,
  kind                TEXT NOT NULL,
  config              TEXT NOT NULL,
  enabled             INTEGER NOT NULL DEFAULT 1,
  last_fetched        TEXT,
  language_code       TEXT NOT NULL DEFAULT 'sv' REFERENCES languages(code),
  auto_download_limit INTEGER NOT NULL DEFAULT 10
);

CREATE TABLE items (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id    INTEGER NOT NULL REFERENCES sources(id),
  external_id  TEXT NOT NULL,
  title        TEXT NOT NULL,
  description  TEXT,
  published_at TEXT,
  audio_url    TEXT,
  audio_path   TEXT,
  duration_ms  INTEGER,
  status       TEXT NOT NULL DEFAULT 'new',
  error        TEXT,
  created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
  UNIQUE (source_id, external_id)
);
CREATE INDEX items_status_idx ON items (status);
CREATE INDEX items_published_idx ON items (published_at DESC);

CREATE TABLE segments (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id  INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  idx      INTEGER NOT NULL,
  start_ms INTEGER NOT NULL,
  end_ms   INTEGER NOT NULL,
  text     TEXT NOT NULL,
  UNIQUE (item_id, idx)
);
CREATE INDEX segments_item_start_idx ON segments (item_id, start_ms);

CREATE TABLE tokens (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id    INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  segment_id INTEGER NOT NULL REFERENCES segments(id) ON DELETE CASCADE,
  idx        INTEGER NOT NULL,
  surface    TEXT NOT NULL,
  normalized TEXT NOT NULL,
  start_ms   INTEGER NOT NULL,
  end_ms     INTEGER NOT NULL,
  is_word    INTEGER NOT NULL DEFAULT 1,
  lemma      TEXT,
  UNIQUE (item_id, idx)
);
CREATE INDEX tokens_item_start_idx ON tokens (item_id, start_ms);
CREATE INDEX tokens_normalized_idx ON tokens (normalized);

CREATE TABLE lexemes (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  language_code TEXT NOT NULL DEFAULT 'sv' REFERENCES languages(code),
  lemma         TEXT NOT NULL,
  pos           TEXT,
  definitions   TEXT NOT NULL,
  origin        TEXT NOT NULL,
  created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now'))
);
-- pos is nullable and NULL != NULL in a unique constraint, so dedupe on a
-- coalesced expression instead.
CREATE UNIQUE INDEX lexemes_uniq
  ON lexemes (language_code, lemma, COALESCE(pos, ''), origin);
CREATE INDEX lexemes_lang_lemma_idx ON lexemes (language_code, lemma);

CREATE TABLE forms (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  language_code TEXT NOT NULL DEFAULT 'sv' REFERENCES languages(code),
  form          TEXT NOT NULL,
  lemma         TEXT NOT NULL,
  pos           TEXT
);
CREATE UNIQUE INDEX forms_uniq
  ON forms (language_code, form, lemma, COALESCE(pos, ''));
CREATE INDEX forms_lang_form_idx ON forms (language_code, form);

CREATE TABLE user_words (
  language_code TEXT NOT NULL DEFAULT 'sv' REFERENCES languages(code),
  lemma         TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'unknown',
  lookup_count  INTEGER NOT NULL DEFAULT 0,
  first_seen    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
  last_seen     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
  PRIMARY KEY (language_code, lemma)
);

CREATE TABLE lookups (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id    INTEGER REFERENCES items(id) ON DELETE SET NULL,
  token_id   INTEGER REFERENCES tokens(id) ON DELETE SET NULL,
  lemma      TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now'))
);
CREATE INDEX lookups_created_idx ON lookups (created_at DESC);

CREATE TABLE progress (
  item_id      INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
  position_ms  INTEGER NOT NULL DEFAULT 0,
  completed    INTEGER NOT NULL DEFAULT 0,
  listen_count INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now'))
);
