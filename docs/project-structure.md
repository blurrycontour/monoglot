# Project structure

A map of the repository for navigation. Read this first, then open the specific
file. Keep it current: when you add, move or repurpose a file or package, update
the matching line here in the same change.

## Top level

```
api/         Go 1.26 backend — API, ingest pipeline, cron, SQLite.
worker/      Python transcription service (faster-whisper + KB-Whisper).
android/     Kotlin/Compose app — the only client.
scripts/     Build/maintenance shell scripts.
docs/        Documentation referenced from AGENTS.md (this file lives here).
data/        Bind-mounted runtime data (audio, cache, raw json, apk). Not code.
docker-compose.yml   One file for dev and prod.
AGENTS.md    Agent-facing overview and the index of docs to keep updated.
README.md    Human-facing overview.
```

## api/ (Go)

```
cmd/api/main.go              Entry point; wires config, db, http, ingest.
internal/
  bootstrap/                 First-start setup: migrations, dictionary, SALDO.
  config/                    Env-var config (matches .env.example).
  db/
    db.go                    SQLite connection, embedded migration runner.
    migrations/*.sql         Schema, applied in filename order.
  httpapi/                   HTTP handlers (chi). One file per area:
    api.go                   Router and middleware wiring.
    items.go                 Item listing/detail/bundle/audio. `status=library`
                             = ready + removed-after-fetch; `archived` =
                             never-fetched back catalogue.
    bundle.go                The inlined per-episode definition bundle.
    words.go                 Vocabulary list and status.
    listening.go             Listening-time stats.
    schedules.go             Ingest schedule CRUD (schedules table).
    model.go                 Transcription model selection/validation.
    system.go                System stats, archive/cleanup, disk usage.
    status.go                Pipeline/queue status.
    download.go appupdate.go dockerstats.go diskfree_unix.go icon.go admin.go
  ingest/                    Pipeline stages over items.status state machine.
    discover.go download.go transcribe.go run.go schedules.go progress.go rss.go
  lexicon/                   Per-language dictionary + morphology providers.
    register.go              Where a new language's providers are registered.
    folkets.go saldo.go translate.go lookup.go language.go
  pipeline/                  Pipeline orchestration helpers.
  srclient/                  Sveriges Radio API client.
```

## worker/ (Python)

```
app.py            Flask-ish service: /transcribe, /validate. Model id per call.
requirements.txt  faster-whisper, etc.
Dockerfile
```

## android/ (Kotlin, Compose, Media3, Room)

```
app/src/main/java/io/blurrycontour/monoglot/
  MainActivity.kt            Activity, nav host, tab pager, theme wiring.
  data/
    ApiClient.kt             REST client. audioUrl carries ?token=.
    Repository.kt            Data access; offline fallback; mediaUri().
    SettingsStore.kt         DataStore settings (speed, theme, text scale,
                             volume, per-episode volume, servers, …).
    Models.kt                DTOs (ItemSummary, Token, Bundle, Candidate, …).
    Graph.kt                 Simple service locator (Graph.repository).
    offline/ (Room)          Downloads, progress, listening cache.
  player/
    PlaybackHolder.kt        Process-wide MediaController owner; position
                             persistence; setVolume() → service.
    PlaybackService.kt       MediaSessionService + ExoPlayer; notification;
                             sentence-skip commands; LoudnessEnhancer volume.
    PlayerViewModel.kt       Player screen state; tap-to-define; word-audio
                             preview player; volume; play-from-word.
  ui/
    screens/
      LibraryScreen.kt       Listen tab: date-grouped list, ArchivedCard,
                             back-catalogue reveal.
      PlayerScreen.kt        Expanded player; transcript views; SentenceText;
                             VolumeSheet; keep-screen-on.
      PlayerControls.kt      Transport, speed/transcript sheets, scrubber.
      WordSheet.kt           Tap-to-define bottom sheet; hear-word / play-from-here.
      SettingsScreen.kt      Settings tab (server, appearance, playback, text
                             size, downloads, reminders, updates).
      SystemScreen.kt        System tab (stats, schedules, model, cleanup).
      WordsScreen.kt         Vocabulary tab.
      EpisodeActions.kt MiniPlayer*.kt UpdateGate.kt …
    theme/
      Theme.kt               MonoglotTheme, TranscriptStyle, LocalTranscriptScale.
    util/
      Dates.kt               Publish-date grouping (Today … back catalogue).
  reminders/                 Alarm scheduling for study reminders.
app/src/test/                JVM unit tests (e.g. DatesTest.kt).
build.gradle.kts settings.gradle.kts gradle/libs.versions.toml
Dockerfile.build             Toolchain image, tagged by its own hash.
```

## scripts/

```
android.sh              Builds the signed release APK in Docker → data/apk/.
session-maintenance.sh  Housekeeping.
```

## Where things live (quick index)

- New HTTP endpoint → `api/internal/httpapi/`, route in `api.go`.
- Schema change → new `api/internal/db/migrations/NNNN_*.sql` (never edit old ones).
- New setting → `android/.../data/SettingsStore.kt` then its screen.
- Playback behaviour → `player/PlaybackHolder.kt` + `player/PlaybackService.kt`.
- New language → implement providers, register in `lexicon/register.go`.
