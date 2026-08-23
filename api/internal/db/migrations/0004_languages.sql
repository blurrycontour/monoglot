-- Multi-language groundwork.
--
-- The app is Swedish-focused and the UI stays single-language for now, but
-- every table that holds language-specific data gets a language_code. Doing
-- this at 20 items is nearly free; doing it once there is a year of content
-- and vocabulary history would be a six-table data migration.

CREATE TABLE languages (
  code       TEXT PRIMARY KEY,          -- ISO 639-1, e.g. 'sv'
  name       TEXT NOT NULL,             -- English name, e.g. 'Swedish'
  native_name TEXT,                     -- e.g. 'Svenska'
  -- Whisper's language hint. Usually the same as code, kept separate because
  -- ASR models do not always agree with ISO 639-1.
  asr_code   TEXT NOT NULL,
  enabled    BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO languages (code, name, native_name, asr_code)
VALUES ('sv', 'Swedish', 'Svenska', 'sv')
ON CONFLICT (code) DO NOTHING;

-- Content sources belong to a language; items inherit it through their source.
ALTER TABLE sources ADD COLUMN language_code TEXT NOT NULL DEFAULT 'sv'
  REFERENCES languages(code);

-- Dictionary and morphology are per-language by definition: "far" is a Swedish
-- noun and an English adverb, and they must not collide.
ALTER TABLE lexemes ADD COLUMN language_code TEXT NOT NULL DEFAULT 'sv'
  REFERENCES languages(code);
ALTER TABLE forms ADD COLUMN language_code TEXT NOT NULL DEFAULT 'sv'
  REFERENCES languages(code);

DROP INDEX IF EXISTS lexemes_lemma_pos_origin_uniq;
CREATE UNIQUE INDEX lexemes_lang_lemma_pos_origin_uniq
  ON lexemes (language_code, lemma, COALESCE(pos, ''), origin);
CREATE INDEX IF NOT EXISTS lexemes_lang_lemma_idx ON lexemes (language_code, lemma);

DROP INDEX IF EXISTS forms_form_lemma_pos_uniq;
CREATE UNIQUE INDEX forms_lang_form_lemma_pos_uniq
  ON forms (language_code, form, lemma, COALESCE(pos, ''));
CREATE INDEX IF NOT EXISTS forms_lang_form_idx ON forms (language_code, form);

-- Vocabulary is per-language: knowing Swedish "hus" says nothing about German
-- "Haus". The primary key has to widen to match.
ALTER TABLE user_words ADD COLUMN language_code TEXT NOT NULL DEFAULT 'sv'
  REFERENCES languages(code);
ALTER TABLE user_words DROP CONSTRAINT user_words_pkey;
ALTER TABLE user_words ADD PRIMARY KEY (language_code, lemma);
