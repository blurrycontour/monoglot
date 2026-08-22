# Svenska Listening Trainer

A personal Swedish listening-comprehension tool. Audio is primary; the
transcript is a crutch you reveal on demand, one sentence at a time.

Built to the spec in [SPEC.md](SPEC.md).

## What it does

Nightly, it pulls new episodes from **SR Klartext** and the **8 Sidor** daily
news podcast, transcribes them with **KB-Whisper** (KBLab's Swedish-finetuned
Whisper) at word-level timestamps, resolves every word to a lemma via **SALDO**
morphology, and warms **Folkets lexikon** definitions for the whole episode.

On the phone you listen with the text hidden. When you fail to parse something,
you reveal that one sentence, tap the word you missed, see the definition
instantly, and replay the sentence. That gap between what you heard and what was
said is the learning event.

## Stack

| Piece | Choice |
|---|---|
| API | Go 1.26, chi, pgx |
| Database | PostgreSQL 16 |
| Transcription | Python + faster-whisper (CTranslate2), `KBLab/kb-whisper-small` |
| Client | Native Android — Kotlin, Jetpack Compose, Material 3, Media3/ExoPlayer, Room |
| Deployment | Docker Compose |

The Android toolchain runs entirely inside a Docker image; nothing is installed
on the host.

## Quick start

```bash
./bootstrap.sh
```

Goes from a clean checkout to a working instance with Klartext episodes fully
processed. It is idempotent — re-running is safe and skips completed work.

Then build the app:

```bash
./scripts/android.sh assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Install it, open **Settings**, and enter the server URL
(`http://<your-lan-ip>:8080`) and the `AUTH_TOKEN` from `.env`.

## Operating it

```bash
docker compose up -d              # start everything
docker compose logs -f api worker # follow
docker compose run --rm api ingest            # full pipeline now
docker compose run --rm api ingest discover   # or a single stage
docker compose run --rm api find-program klartext   # if SR renumbers the program
cd api && GOWORK=off go test ./...
```

Ingestion also runs in-process nightly at `INGEST_CRON_HOUR:INGEST_CRON_MINUTE`
(default 03:30, Europe/Stockholm).

## How the pipeline works

Items move through a state machine, so any stage can crash and be retried
without redoing prior work — which matters, because transcription is the slow
step:

```
new → downloading → downloaded → transcribing → ready
                         ↓            ↓
                       failed ←───────┘
```

Transcripts are generated **from the audio**, never from the publisher's
manuscript. Klartext publishes a script, but it is not word-aligned and does not
match the read audio exactly. Generating from audio makes alignment correct by
construction.

Definitions are resolved at **ingestion** time, not at tap time. The
`/api/items/:id/bundle` endpoint returns one JSON blob with metadata, segments,
tokens, and the full definitions for every distinct lemma in the episode
inlined. The client stores that whole thing, which is what makes tap-to-define
work on a bus with no signal.

## Measured on this instance

- Lookup latency: **~1 ms** median (budget was 100 ms)
- Transcription: **~3.3× realtime** on CPU with `kb-whisper-small` int8
- Lemma resolution: **92.5%** of word tokens
- Offline definition coverage: **90.1%** of word tokens per episode
- SALDO: 1.67M inflected forms; Folkets: 36,876 lexemes

## Deliberately not built

Spaced repetition (export to Anki instead), pronunciation scoring, speech input,
content recommendation, multi-user support, grammar exercises. See §2 of the
spec.

## Licensing and attributions

This instance is **personal and private**. Do not deploy it publicly and do not
redistribute cached audio.

- **Sveriges Radio** — Klartext audio and metadata. SR's terms intend
  linking/streaming from SR's own servers; local caching for personal listening
  is acceptable for a private single-user instance and is *not* acceptable for
  anything public. SR material may not be used for machine learning without
  prior approval.
- **8 Sidor** — daily podcast audio.
- **Folkets lexikon** (KTH/CSC) — CC BY-SA 2.5.
- **SALDO** (Språkbanken, University of Gothenburg) — CC BY-SA 2.5 / LGPL 3.0.
- **KB-Whisper** (KBLab, National Library of Sweden).

All five are shown on the About section of the Settings screen.
