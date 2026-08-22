-- Svenska Listening Trainer: initial schema.
-- Single user, so no user_id columns anywhere.

CREATE TABLE sources (
  id           SERIAL PRIMARY KEY,
  slug         TEXT UNIQUE NOT NULL,
  name         TEXT NOT NULL,
  kind         TEXT NOT NULL,
  config       JSONB NOT NULL,
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  last_fetched TIMESTAMPTZ
);

CREATE TABLE items (
  id            SERIAL PRIMARY KEY,
  source_id     INT NOT NULL REFERENCES sources(id),
  external_id   TEXT NOT NULL,
  title         TEXT NOT NULL,
  description   TEXT,
  published_at  TIMESTAMPTZ,
  audio_url     TEXT,
  audio_path    TEXT,
  duration_ms   INT,
  status        TEXT NOT NULL DEFAULT 'new',
  error         TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_id, external_id)
);
CREATE INDEX ON items (status);
CREATE INDEX ON items (published_at DESC);

CREATE TABLE segments (
  id        SERIAL PRIMARY KEY,
  item_id   INT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  idx       INT NOT NULL,
  start_ms  INT NOT NULL,
  end_ms    INT NOT NULL,
  text      TEXT NOT NULL,
  UNIQUE (item_id, idx)
);
CREATE INDEX ON segments (item_id, start_ms);

CREATE TABLE tokens (
  id          SERIAL PRIMARY KEY,
  item_id     INT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  segment_id  INT NOT NULL REFERENCES segments(id) ON DELETE CASCADE,
  idx         INT NOT NULL,
  surface     TEXT NOT NULL,
  normalized  TEXT NOT NULL,
  start_ms    INT NOT NULL,
  end_ms      INT NOT NULL,
  is_word     BOOLEAN NOT NULL DEFAULT TRUE,
  lemma       TEXT,
  UNIQUE (item_id, idx)
);
CREATE INDEX ON tokens (item_id, start_ms);
CREATE INDEX ON tokens (normalized);

CREATE TABLE lexemes (
  id          SERIAL PRIMARY KEY,
  lemma       TEXT NOT NULL,
  pos         TEXT,
  definitions JSONB NOT NULL,
  origin      TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON lexemes (lemma);
-- pos is nullable and NULL != NULL in a UNIQUE constraint, so dedupe on a
-- coalesced expression instead; without this, re-running the importer would
-- insert duplicate rows for every entry that has no part of speech.
CREATE UNIQUE INDEX lexemes_lemma_pos_origin_uniq
  ON lexemes (lemma, COALESCE(pos, ''), origin);

CREATE TABLE forms (
  id     SERIAL PRIMARY KEY,
  form   TEXT NOT NULL,
  lemma  TEXT NOT NULL,
  pos    TEXT
);
CREATE INDEX ON forms (form);
CREATE UNIQUE INDEX forms_form_lemma_pos_uniq
  ON forms (form, lemma, COALESCE(pos, ''));

CREATE TABLE user_words (
  lemma        TEXT PRIMARY KEY,
  status       TEXT NOT NULL DEFAULT 'unknown',
  lookup_count INT NOT NULL DEFAULT 0,
  first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lookups (
  id         SERIAL PRIMARY KEY,
  item_id    INT REFERENCES items(id) ON DELETE SET NULL,
  token_id   INT REFERENCES tokens(id) ON DELETE SET NULL,
  lemma      TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON lookups (created_at DESC);

CREATE TABLE progress (
  item_id      INT PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
  position_ms  INT NOT NULL DEFAULT 0,
  completed    BOOLEAN NOT NULL DEFAULT FALSE,
  listen_count INT NOT NULL DEFAULT 0,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
