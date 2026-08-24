# Monoglot

A personal listening-comprehension trainer. Swedish today, built to take more languages. Audio is primary; the
transcript is a crutch you reveal on demand, one sentence at a time.

Built to the spec in [SPEC.md](SPEC.md).

## What it does

Nightly, it pulls new episodes from four Swedish sources — **SR Klartext**,
the **8 Sidor** daily news podcast, **Vetenskapsradion Nyheter** (daily science
news), and **Forskning & Framsteg: Artiklar** (read magazine articles) — transcribes them with **KB-Whisper** (KBLab's Swedish-finetuned
Whisper) at word-level timestamps, resolves every word to a lemma via **SALDO**
morphology, and warms **Folkets lexikon** definitions for the whole episode.

On the phone you listen with the text hidden. When you fail to parse something,
you reveal that one sentence, tap the word you missed, see the definition
instantly, and replay the sentence. That gap between what you heard and what was
said is the learning event.

## Stack

| Piece | Choice |
|---|---|
| API | Go 1.26, chi, database/sql |
| Database | SQLite (modernc.org/sqlite, pure Go) |
| Transcription | Python + faster-whisper (CTranslate2), `KBLab/kb-whisper-small` |
| Client | Native Android — Kotlin, Jetpack Compose, Material 3, Media3/ExoPlayer, Room |
| Deployment | Docker Compose |

The Android toolchain runs entirely inside a Docker image; nothing is installed
on the host.

## Running it

There is no install script. The server sets itself up on first start:
migrations, then Folkets lexikon, then the SALDO word forms, then the first
fetch of episodes. It is idempotent — an interrupted first start picks up where
it left off, and each step is skipped once recorded. Progress shows on the
**System** tab and in `docker compose logs -f api`.

Configuration is one file. `.env` is handed whole to both services with
`env_file`, so there is no second list of defaults in the compose file to keep
in step; `.env.example` documents every variable the stack reads.

**On a production host** — this file, `docker-compose.yml` and `.env` are all
you need; no checkout, no toolchain:

```bash
cp .env.example .env
sed -i "s|^AUTH_TOKEN=.*|AUTH_TOKEN=$(openssl rand -hex 32)|" .env
docker compose pull
docker compose up -d
```

**On a development machine**, build from source instead:

```bash
docker compose up -d --build
```

Both use the same `docker-compose.yml`. `./data` is a bind mount in both cases,
so the database, cached audio and the APK are ordinary files you can back up
and copy.

First start downloads ~250MB of SALDO and imports about a million word forms,
which takes a few minutes. Transcription is slower still and continues in the
background — episodes appear in the app as they finish, and the library screen
shows how many are still processing.

### Getting the app onto the phone

Open `http://<server-ip>:8080/download` and tap Download. Then open the app, go
to **Settings**, and enter the server URL and the `AUTH_TOKEN` from `.env`.

A production server has no build toolchain, so CI bakes the signed APK into the
`api` image and `/download` serves that. A development machine serves whatever
`scripts/android.sh` last put in `./data/apk/`, which takes precedence — a local
build is available the moment it finishes, with no image rebuild.

```bash
./scripts/android.sh          # signed release APK -> data/apk/
```

### Updating the app

Builds are signed with a persistent key at `android/release.keystore` and carry
a `versionCode` derived from the clock (minutes since 2024), so Android installs
each new build over the last one, keeping downloads and settings. The clock is
what makes local and CI builds interchangeable: a build made later always
outranks one made earlier, wherever it was built, so you can install a CI build
today and a local one tomorrow without them fighting.

The same key is used by CI, so one installed app can be pointed at either
server. To upload it, see [Signing key in CI](#signing-key-in-ci).

**Back up `android/release.keystore` and `android/keystore.properties`.** Both
are gitignored because they are secrets. If you lose them, Android will reject
every future update and you would have to uninstall the app to reinstall it.

Debug builds do not have this property: the container's debug keystore is
regenerated on every run, so each debug APK is signed by a different key and
Android treats it as a different app.

## Operating it

```bash
docker compose up -d              # start everything
docker compose logs -f api worker # follow
docker compose run --rm api ingest            # full pipeline now
docker compose run --rm api ingest discover   # or a single stage
docker compose run --rm api find-program klartext   # if SR renumbers the program
cd api && GOWORK=off go test ./...
```

The dictionary and morphology imports record themselves in the `imports` table
and skip on every later start. To reimport after a dataset update:

```bash
docker compose run --rm api import-morphology --force
```

Ingestion also runs in-process nightly at `INGEST_CRON_HOUR:INGEST_CRON_MINUTE`
(default 03:30, Europe/Stockholm).

## Signing key in CI

CI builds a **debug** APK without these secrets, and refuses to bake a debug
build into the image — a debug key can never update an installed app. Upload
the existing key rather than making a new one, so CI and this machine produce
interchangeable builds:

```bash
# from the repo root
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 android/release.keystore)
gh secret set ANDROID_STORE_PASSWORD  --body "$(grep '^storePassword=' android/keystore.properties | cut -d= -f2-)"
gh secret set ANDROID_KEY_ALIAS       --body "$(grep '^keyAlias='       android/keystore.properties | cut -d= -f2-)"
gh secret set ANDROID_KEY_PASSWORD    --body "$(grep '^keyPassword='    android/keystore.properties | cut -d= -f2-)"
```

This puts the key that controls updates to your phone in GitHub's hands, and
any workflow that can run in this repository can sign as you. That is the price
of CI producing installable builds; if you would rather not pay it, leave the
secrets unset and copy the APK to the server yourself.

## Container images

`main` publishes `ghcr.io/blurrycontour/monoglot-api` and `-worker`, tagged
`latest` and by commit sha. Pull requests build both without pushing. Point a
host at a different registry or a pinned build with `IMAGE_REGISTRY` and
`IMAGE_TAG` in `.env`.

GHCR packages are private by default. Either make them public once, or
`docker login ghcr.io` on the host with a `read:packages` token.

## How the pipeline works

Items move through a state machine, so any stage can crash and be retried
without redoing prior work — which matters, because transcription is the slow
step:

```
new → downloading → downloaded → transcribing → ready
                         ↓            ↓
                       failed ←───────┘
```

Sveriges Radio publishes the same Klartext episode once per airing (19:00 and
21:00), each with its own episode id and audio URL but identical content, so
deduping on `(source_id, external_id)` alone lets a second copy of every
episode through. Sources that air once per day set `"one_per_day": true` in
their config and are additionally deduped by publish date.

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
- SALDO: 1.04M inflected forms; Folkets: 36,876 lexemes
- First start on an empty database: ~90s with SALDO cached, a few minutes without

Note: transcription and the Android build are both disk-hungry. Keep several GB
free; `docker builder prune -af` reclaims the most.

## Multi-language

Swedish is the only language wired up, but the schema and API are already
per-language: `language_code` scopes sources, dictionary, morphology and
vocabulary, and `languages` holds each language's ASR hint.

Adding a language means implementing `lexicon.DictionaryProvider` and
`lexicon.MorphologyProvider` and registering them in
`api/internal/lexicon/register.go`. No schema migration required.

## Sources

| Source | Kind | Notes |
|---|---|---|
| SR Klartext | SR API (493) | ~5 min daily news, slow and clear |
| 8 Sidor | Acast-style RSS | daily news podcast, human read |
| Vetenskapsradion Nyheter | SR API (406) | ~5 min daily science news |
| Forskning & Framsteg: Artiklar | Acast RSS | ~9 min read articles. Archive: no new episodes since Feb 2024 |

fof.se's own `/feed` carries articles with no audio, and the site embeds
Spotify players rather than hosting files. The Acast RSS behind those embeds is
the real feed.

Only the newest `auto_download_limit` items per source (default 10) are
downloaded automatically. Older items are marked `archived` — known about, no
audio on disk, fetchable on demand from the app. That keeps a 175-episode
archive from filling the disk on first sync.

## Managing storage

The **System** tab shows what is finished, what is in progress, and what is
using disk, broken down per source.

Two independent things use space:

- **Server** — audio and transcripts. "Free up space from old episodes" removes
  them for episodes older than a chosen window. The episode stays in the
  library and can be fetched again on demand; anything you have started is
  skipped.
- **Phone** — offline downloads, managed with the **Save** button on each
  episode and cleared in bulk from the System tab.

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
- **MyMemory** — machine-translation fallback for words the dictionary lacks,
  used within its anonymous quota. `TRANSLATE_FALLBACK=0` disables it.
- **SALDO** (Språkbanken, University of Gothenburg) — CC BY-SA 2.5 / LGPL 3.0.
- **KB-Whisper** (KBLab, National Library of Sweden).

All five are shown on the About section of the Settings screen.
