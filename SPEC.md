# Svenska Listening Trainer — v1 Build Spec

**Read this whole document before writing code.**

You are building v1 of a personal Swedish listening-comprehension app. Single user, self-hosted, runs in a homelab. This spec is deliberately narrow. Ship exactly what is in scope, nothing more.

---

## 1. The problem this solves

The user has lived in Sweden 6+ years, reads Swedish comfortably, but cannot parse fluent spoken Swedish. The gap is **speech perception**, not vocabulary. Spoken Swedish deviates heavily from written form (`de`/`dem` → *dom*, `vad` → *va*, `något` → *nåt*, `jag vet inte` → *jaevetinte*).

The app fixes this with **aligned audio + text**, where the text is hidden by default. The user listens, fails to parse something, reveals just that sentence, sees the mismatch between what they heard and what was said, and re-listens. That gap is the learning event.

Everything in the design serves that loop. Features that don't serve it are out of scope.

### Design principles

1. **Audio is primary. Text is a crutch you reveal on demand.** Never show transcript by default.
2. **Lookup friction is the enemy.** Tapping a word must feel instant (<100 ms). This is the single most important UX property in the app.
3. **Bus mode must work.** Full offline: audio, transcript, and definitions, with the screen off.
4. **Boring and finished beats clever and unfinished.** No feature creep.

---

## 2. Hard scope boundaries

### In scope for v1

- Nightly ingestion from two sources: **SR Klartext** and **8 Sidor**
- Word-level forced alignment via Whisper
- Audio player with word-level highlight
- Three transcript visibility modes: hidden / reveal-current-sentence / full
- Tap any word → English definition popup, offline-capable
- Mark word known/unknown
- Per-item playback position persistence
- Offline download of individual items
- Android build via Capacitor

### Explicitly OUT of scope — do not build these

| Not building | Why |
|---|---|
| Spaced repetition system | Anki exists and is better. Export instead. |
| Pronunciation scoring | Whisper is trained to be accent-robust; it will transcribe mispronounced Swedish as the correct word. Real scoring needs phoneme-level GOP against an acoustic model. Different project. |
| Speech input of any kind | Deferred to v2. Not in v1. |
| Content recommendation / embeddings / pgvector | Ten hand-picked feeds cover it. This is the most seductive and least necessary component. |
| Multi-user, accounts, registration | Single user. |
| Additional content sources | Two sources. Prove the loop first. |
| Grammar explanations, exercises, quizzes | Not the bottleneck. |

If you find yourself adding something not listed as in-scope, stop and ask.

---

## 3. Stack

Match the user's existing homelab conventions:

- **Backend:** Go (stdlib `net/http` or chi; no heavy framework)
- **DB:** PostgreSQL
- **Transcription worker:** Python (separate service — Whisper tooling is Python-only)
- **Frontend:** React + Vite + TypeScript + Tailwind + shadcn/ui
- **Mobile:** Capacitor (Android target)
- **LLM fallback:** Ollama (already running in the homelab)
- **Deployment:** Docker Compose

Audio files live on the filesystem, served by the Go backend. No object store.

Auth: a single static bearer token in an env var, checked by middleware. Nothing more. This service must not be exposed publicly (see §10).

---

## 4. External dependencies — all verified

### 4.1 Content: Sveriges Radio Open API

- Base: `https://api.sr.se/api/v2`
- Docs: `https://api.sr.se/api/documentation/v2/index.html`
- Returns JSON with `?format=json`
- Officially **unmaintained but functional**. Expect rough edges; handle errors defensively.

**Klartext** is the target program: ~10 min daily news read slowly and clearly. Find its program ID via the `/programs` endpoint (search by name — do not hardcode a guessed ID), then pull episodes via `/episodes/index?programid=<id>`. Episode objects carry audio URLs (`listenpodfile` / `downloadpodfile`).

### 4.2 Content: 8 Sidor

- Article RSS: `https://8sidor.se/feed/` (WordPress feed)
- There is also a podcast RSS with human-read audio, and articles on the site have a per-article "Lyssna" audio.

**Build-time discovery task:** determine whether per-article audio URLs are reachable from the RSS feed or require scraping the article page. Inspect the actual feed and one article page before writing the ingester. If per-article audio proves unreliable, fall back to treating the daily podcast episode as a single item and drop 8 Sidor article-level audio from v1 — do not build a fragile scraper.

### 4.3 Transcription: KB-Whisper

Use **KBLab's Swedish-finetuned Whisper**, not vanilla OpenAI Whisper. It is dramatically better on Swedish — `kb-whisper-small` outperforms `openai/whisper-large-v3` on Swedish benchmarks, and `kb-whisper-large` roughly halves WER versus OpenAI's equivalent.

- Models: `KBLab/kb-whisper-small`, `-medium`, `-large` on Hugging Face
- CTranslate2 checkpoints are provided (usable by `faster-whisper` / WhisperX). **Read the model card to get the correct checkpoint path/revision — do not guess it.**
- Default to `kb-whisper-small` for iteration speed; make model size an env var.

**Alignment approach:**

Start with `faster-whisper` and `word_timestamps=True`. That is sufficient for v1 and much simpler.

If word timing proves too loose in practice, upgrade to WhisperX forced alignment using `KBLab/wav2vec2-large-voxpopuli-sv-swedish` as the alignment model. Do not build this upgrade preemptively.

**Do not use the publisher's own manuscript as the transcript.** Klartext publishes a script, but it is not word-aligned and won't match the read audio exactly. Generating the transcript from the audio means alignment is correct by construction. This is the single most important architectural decision in the ingestion pipeline.

### 4.4 Dictionary: Folkets lexikon

- `https://folkets-lexikon.csc.kth.se/folkets/folkets_sv_en_public.xml`
- ~89,000 Swedish entries, Swedish→English
- **License: CC BY-SA 2.5** — attribution required in the app UI
- Custom XML schema; inspect the file before writing the parser

### 4.5 Morphology: SALDO

Needed because Swedish inflection breaks naive dictionary lookup: `husen` → `hus`, `gick` → `gå`, `bättre` → `bra`.

- SALDO's morphology (`saldom`) from Språkbanken: `https://spraakbanken.gu.se/en/resources/saldom`
- Provides full-form → lemma mappings
- **License: CC BY-SA 2.5 or LGPL 3.0**
- Note: the SALDO *web service* was shut down in 2021. Use the **downloadable dataset only.** This is better anyway — local lookup is what makes tap-to-define instant and offline-capable.

---

## 5. Data model

Single-user, so no `user_id` columns anywhere.

```sql
-- Content sources
CREATE TABLE sources (
  id           SERIAL PRIMARY KEY,
  slug         TEXT UNIQUE NOT NULL,      -- 'klartext', '8sidor'
  name         TEXT NOT NULL,
  kind         TEXT NOT NULL,             -- 'sr_api' | 'rss'
  config       JSONB NOT NULL,            -- program id, feed url, etc.
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  last_fetched TIMESTAMPTZ
);

-- A single listenable piece of content
CREATE TABLE items (
  id            SERIAL PRIMARY KEY,
  source_id     INT NOT NULL REFERENCES sources(id),
  external_id   TEXT NOT NULL,            -- dedupe key from source
  title         TEXT NOT NULL,
  description   TEXT,
  published_at  TIMESTAMPTZ,
  audio_url     TEXT,                     -- original remote URL
  audio_path    TEXT,                     -- local file path once downloaded
  duration_ms   INT,
  status        TEXT NOT NULL DEFAULT 'new',
                -- new | downloading | downloaded | transcribing
                -- | ready | failed
  error         TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_id, external_id)
);

-- Sentence / phrase level. The unit of "reveal".
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

-- Word level. Drives highlight and tap-to-define.
CREATE TABLE tokens (
  id          SERIAL PRIMARY KEY,
  item_id     INT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  segment_id  INT NOT NULL REFERENCES segments(id) ON DELETE CASCADE,
  idx         INT NOT NULL,               -- index within item
  surface     TEXT NOT NULL,              -- as spoken/written
  normalized  TEXT NOT NULL,              -- lowercased, punctuation stripped
  start_ms    INT NOT NULL,
  end_ms      INT NOT NULL,
  is_word     BOOLEAN NOT NULL DEFAULT TRUE,
  lemma       TEXT,                       -- resolved at ingestion
  UNIQUE (item_id, idx)
);
CREATE INDEX ON tokens (item_id, start_ms);
CREATE INDEX ON tokens (normalized);

-- Dictionary entries (Folkets + LLM fallback)
CREATE TABLE lexemes (
  id          SERIAL PRIMARY KEY,
  lemma       TEXT NOT NULL,
  pos         TEXT,
  definitions JSONB NOT NULL,   -- [{translation, comment, example}]
  origin      TEXT NOT NULL,    -- 'folkets' | 'llm'
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (lemma, pos, origin)
);
CREATE INDEX ON lexemes (lemma);

-- Inflected form -> lemma, from SALDO
CREATE TABLE forms (
  id     SERIAL PRIMARY KEY,
  form   TEXT NOT NULL,
  lemma  TEXT NOT NULL,
  pos    TEXT,
  UNIQUE (form, lemma, pos)
);
CREATE INDEX ON forms (form);

-- User's vocabulary state, keyed on lemma
CREATE TABLE user_words (
  lemma       TEXT PRIMARY KEY,
  status      TEXT NOT NULL DEFAULT 'unknown',  -- unknown | learning | known
  lookup_count INT NOT NULL DEFAULT 0,
  first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every tap, for later analysis and Anki export
CREATE TABLE lookups (
  id         SERIAL PRIMARY KEY,
  item_id    INT REFERENCES items(id) ON DELETE SET NULL,
  token_id   INT REFERENCES tokens(id) ON DELETE SET NULL,
  lemma      TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Resume position
CREATE TABLE progress (
  item_id      INT PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
  position_ms  INT NOT NULL DEFAULT 0,
  completed    BOOLEAN NOT NULL DEFAULT FALSE,
  listen_count INT NOT NULL DEFAULT 0,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 6. Ingestion pipeline

Runs nightly (cron in the Go service, or an n8n workflow hitting an endpoint — either is fine; prefer in-process cron for fewer moving parts).

Model it as a **state machine over `items.status`**, so any stage can crash and be retried without redoing prior work. This matters: transcription is slow and you do not want a failure at the alignment step to force a re-download.

```
new → downloading → downloaded → transcribing → ready
                          ↓            ↓
                        failed ←───────┘
```

**Stage 1 — Discover.** For each enabled source, fetch the feed/API. Insert new `items` with `status='new'`, deduping on `(source_id, external_id)`.

**Stage 2 — Download.** For `status='new'`: fetch audio to `/data/audio/<item_id>.mp3`, probe duration with ffprobe, set `status='downloaded'`.

**Stage 3 — Transcribe.** For `status='downloaded'`: the Go service POSTs the audio path to the Python worker. Worker runs KB-Whisper with word timestamps and returns:

```json
{
  "segments": [
    {
      "start": 0.0, "end": 4.2,
      "text": "Regeringen har beslutat om nya regler.",
      "words": [
        {"word": "Regeringen", "start": 0.12, "end": 0.71},
        {"word": "har", "start": 0.71, "end": 0.88}
      ]
    }
  ]
}
```

Go persists `segments` and `tokens`. Convert seconds → integer milliseconds at the boundary; do not store floats.

**Stage 4 — Lemmatize.** For each token, resolve `lemma` via the `forms` table (§7). Store on the token so the read path never does morphology work.

**Stage 5 — Warm the dictionary.** For every distinct lemma in the item, ensure a `lexemes` row exists. Cache misses to the LLM here, at ingestion time, **not** at tap time. This is what makes taps instant.

Set `status='ready'`.

### Bootstrap commands (one-time, idempotent)

- `import-dictionary` — download and parse Folkets XML into `lexemes`
- `import-morphology` — download and parse SALDO into `forms`

---

## 7. Word lookup

The read path, on tap. Must be fast.

1. Token already carries `lemma` (resolved at ingestion) → look up `lexemes` by lemma. Done.
2. If `lemma` is null (ingestion miss), resolve live:
   - Exact match on `forms.form = normalized`
   - Fall back to `lexemes.lemma = normalized` (already a base form)
   - Fall back to Ollama, then **write the result into `lexemes` with `origin='llm'`** so it's free next time

Ambiguity is real — `får` is both *sheep* and *may/gets*. When `forms` returns multiple lemmas, return all candidates and let the UI show them stacked. Do not try to disambiguate by context in v1.

**Compounds.** Swedish compounds (`arbetsmarknadsinstitut`, `sjukvårdsförsäkring`) will miss both Folkets and SALDO. These fall through to the LLM path. Prompt Ollama explicitly to split the compound and gloss each part — that's more useful than a single fuzzy translation. Keep the prompt in a config file, not hardcoded.

---

## 8. API surface

```
GET    /api/items?source=&status=ready&limit=&offset=
GET    /api/items/:id            → metadata + segments + tokens
GET    /api/items/:id/bundle     → EVERYTHING for offline (see below)
GET    /api/items/:id/audio      → audio file, must support Range requests
GET    /api/lookup?w=<surface>   → definitions (live fallback path)
POST   /api/words/:lemma/status  → {status}
POST   /api/items/:id/progress   → {position_ms, completed}
GET    /api/export/anki          → .apkg or CSV of learning words
POST   /api/admin/ingest         → trigger pipeline manually
GET    /api/health
```

**Range request support on the audio endpoint is mandatory** — seeking and mobile playback both depend on it. Use `http.ServeFile`, which handles this correctly, rather than writing bytes manually.

**The bundle endpoint is the key to offline.** It returns one JSON blob containing item metadata, all segments, all tokens, **and the full definitions for every distinct lemma in the item, inlined.** The client stores this whole thing. That is what makes tap-to-define work on the bus with no signal. Do not make the client resolve definitions individually.

---

## 9. Frontend

### Screens

1. **Library** — list of ready items, newest first, source filter, progress indicator, download-for-offline toggle per item
2. **Player** — the only screen that matters
3. **Words** — list of looked-up words with status filter, Anki export button
4. **Settings** — playback speed default, transcript default mode, source toggles, ingestion trigger

### Player: the core screen

Layout, top to bottom: title/source → transcript area → controls.

**Transcript visibility — three modes, toggled by one button:**

| Mode | Behaviour |
|---|---|
| `hidden` (default) | No text at all. Just audio. |
| `reveal` | Blank until the user taps "show this sentence" — reveals only the currently-playing segment, then re-hides when the segment ends |
| `full` | Full transcript, current word highlighted |

Default must be `hidden`. This is the whole point of the app; do not default to `full` because it demos better.

**Controls:**

- Play/pause
- **Replay current segment** — the most-used button after play. Give it prominence and a large touch target.
- Back 5s / forward 5s
- Speed: 0.75× / 0.85× / 1.0× (use `playbackRate`; do not build pitch correction)
- Segment navigation: previous/next

**Word highlighting.** On `timeupdate`, find the active token. Binary-search a time-sorted token array held in memory — do not scan linearly, and do not query the server. Throttle to ~10 Hz; 60 Hz updates will burn battery for no perceptible gain.

**Tap-to-define.** Tap a word → popover with lemma, part of speech, definitions, and a known/unknown toggle. Popover must not pause audio and must not shift layout. Resolve from the in-memory bundle first; only hit the network on a miss.

**Background playback.** Use the Media Session API so lock-screen controls and headphone buttons work. The user listens while running and lifting — if playback dies when the screen locks, the app is useless.

### Offline

Per item: download audio blob + bundle JSON → IndexedDB. Show clear downloaded/not state and a total-storage figure. Provide a "clear downloads" action.

Service worker caches the app shell. Playback of a downloaded item must work with the network fully off — **test this by actually enabling airplane mode**, not by mocking `navigator.onLine`.

---

## 10. Legal and licensing — read this

This instance is **personal and private**. Do not deploy it publicly, do not add authentication for other users, do not redistribute cached audio.

- **Sveriges Radio API terms:** SR states material may not be used for machine learning without prior approval, and that linking/streaming from SR's own servers is the intended usage. Local caching for personal listening is a gray area that is fine for a private single-user instance and *not* fine for anything public. Attribution to Sveriges Radio must be displayed on any item sourced from SR.
- **Folkets lexikon:** CC BY-SA 2.5. Display attribution in the UI (Settings/About is fine) with a link to `https://folkets-lexikon.csc.kth.se/`.
- **SALDO:** CC BY-SA 2.5 or LGPL 3.0. Attribute Språkbanken, University of Gothenburg.
- **KB-Whisper:** attribute KBLab, National Library of Sweden.

Add an **About** screen carrying all four attributions. Do this in the milestone where you first use each resource, not "later."

---

## 11. Milestones

Build in this order. Each milestone must be independently verifiable before starting the next.

**M0 — Skeleton.** Docker Compose (postgres, go-api, py-worker, web). Migrations. Health checks green. No features.

**M1 — Dictionary and morphology.** Import Folkets and SALDO. Verify: `curl /api/lookup?w=husen` returns the definition for `hus`. Verify `gick` → `gå`. If these two don't work, everything downstream is broken.

**M2 — Ingestion.** SR API client, Klartext discovery, audio download. Verify: three Klartext episodes in `items` with `status='downloaded'` and playable local files.

**M3 — Transcription.** Python worker with KB-Whisper. Verify: an item reaches `status='ready'` with sensible segments and tokens, and spot-check that word timestamps actually line up with the audio.

**M4 — Player.** Web UI, all three transcript modes, tap-to-define, segment replay. **This is the milestone where the app becomes real.** Do not shortcut it to get to the interesting backend work.

**M5 — Offline.** Bundle endpoint, IndexedDB, service worker. Airplane-mode test.

**M6 — Android.** Capacitor wrap, background audio, Media Session, install on device.

**Then stop.** Ship M6, use the app daily for three weeks, and only then consider new features — and only ones that address something actually noticed during use.

---

## 12. Notes for the implementer

- **Milliseconds as integers everywhere.** Floats for timestamps will produce off-by-one highlight bugs that are miserable to debug.
- **Make transcription resumable and idempotent.** It's the slow step. Re-running ingestion must not re-transcribe completed items.
- **Log timing on the tap-to-define path** during development. If it exceeds 100 ms, fix it before moving on — that latency is the difference between an app that gets used and one that doesn't.
- **Store raw Whisper JSON output** alongside the parsed rows during development. When alignment looks wrong you will want the original.
- Handle SR API failures gracefully and loudly. It is unmaintained; it will misbehave.
- Seed `sources` via a migration, not manual SQL.
- Write a `make bootstrap` that goes from clean checkout to a working instance with one Klartext episode fully processed.

### Questions to ask rather than guess

- If the Klartext program ID cannot be found via search
- If 8 Sidor per-article audio requires fragile scraping (drop the feature instead)
- If KB-Whisper CTranslate2 checkpoint paths differ from the model card
- If `faster-whisper` word timestamps prove too imprecise to highlight usefully

Do not silently substitute a different approach. Ask.
