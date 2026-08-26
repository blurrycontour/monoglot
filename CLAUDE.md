# Monoglot

Personal, single-user Swedish listening-comprehension app, self-hosted in a
homelab. `NOT-BUILDING.md` lists what was deliberately turned down, and why.

## The one idea

Audio is primary; the transcript is a crutch revealed on demand. Listen, fail to
parse a sentence, reveal that sentence, tap the word you missed, re-listen.
Two consequences that are easy to break:

- **Transcript defaults to hidden.** Never default to full text because it demos better.
- **Tap-to-define must feel instant.** Definitions are resolved at ingestion and
  inlined into the item bundle, so a tap never waits on the network.

## Layout

```
api/       Go 1.26 — chi, database/sql + SQLite. API, pipeline, cron.
worker/    Python — faster-whisper + KB-Whisper. Transcription only.
android/   Kotlin, Compose, Media3, Room. The only client.
scripts/   android.sh builds the APK inside Docker.
```

```bash
docker compose up -d --build                # dev; prod does `pull && up -d`
./scripts/android.sh                        # signed release APK -> data/apk/
docker compose run --rm api ingest discover # or download | transcribe | all
cd api && GOWORK=off go test ./...
```

One compose file for both machines, `./data` bind-mounted in both. There is no
install script: `internal/bootstrap` imports the dictionary and word forms on
first start, so a prod host needs only `docker-compose.yml` and a `.env`. CI
bakes the signed APK into the api image; on disk it takes precedence, so a
local build is served at once.

`GOWORK=off` is mandatory: a parent `go.work` at `~/repos/go.work` otherwise
captures this module.

`applicationId` and the Kotlin package are both `io.blurrycontour.monoglot`; the
Go module is `github.com/blurrycontour/monoglot`. Changing `applicationId` means
a fresh install, not an update: local settings and downloads go. The keystore
alias is still `svenska` and cannot change — it is baked into the key.

`.env` is the only list of settings: compose hands the whole file to both
services with `env_file`, so never add an `environment:` block. Defaults in Go
match `.env.example` and exist only so the binary runs outside compose.

## Traps

- **Milliseconds as integers everywhere.** Floats cause off-by-one highlight
  bugs. Seconds→ms conversion happens once, at the worker boundary.
- **Transcripts come from the audio, never the publisher's manuscript.**
  Klartext's published script does not match the read audio.
- **The pipeline is a state machine over `items.status`**
  (`new → downloading → downloaded → transcribing → ready`, plus `archived`).
  Every stage must be independently retryable: transcription is slow and must
  never be redone because a later stage failed.
- **The pipeline only self-starts via the watchdog** (`Runner.StartWatchdog`,
  every 5 min). The nightly cron alone left any stall parked until 03:30.
  Anything that counts pending work must match what the stage actually selects,
  or the drain loop sees no progress and exits.
- **SR republishes each episode per airing**, same content, different
  `external_id`. Sources set `"one_per_day": true` to dedupe by publish date.
- **SQLite needs `ANALYZE` after a bulk import.** Without `sqlite_stat1` the
  planner drives the lookup join from `lexemes`: 12.5ms per lookup versus
  0.006ms. `db.EnsureStats` runs it on first startup.
- **SQLite placeholders are positional.** One argument per `?`, in text order —
  unlike Postgres `$1`, which can repeat. Converting between them silently
  reorders arguments; this caused three real bugs.
- **Anchor `.gitignore` patterns with a leading slash.** An unanchored `data/`
  matched the app's own `data` package and kept it out of git for weeks.
- **Never pipe into `head` under `set -o pipefail`.** SIGPIPE fails the script
  even though the read succeeded — twice now: a truncated SALDO import and a
  corrupt APK version manifest.
- **`scripts/android.sh` publishes only on release tasks** and reads the version
  back out of the built APK. A stale APK under a fresh versionCode makes the
  in-app updater loop forever.
- **The Android toolchain image is tagged by a hash of `android/Dockerfile.build`**
  and pulled from GHCR when it exists. Change that file and the next build makes
  a new one; change anything else and it is reused.
- **`versionCode` is minutes since 2024**, so a later build always outranks an
  earlier one wherever it was built. Do not make it a commit count.
- **Disk is tight**; `docker builder prune -af` reclaims the most. Always check
  build exit status — a failure piped through `tail` silently keeps the old
  image running.
- **Nothing in the app may poll while backgrounded.** The position ticker runs
  only while playing, and status polling only while its tab is foreground and
  visible; both used to run for the life of the process, which cost 24% of a
  battery in ten hours.
- Range requests on the audio endpoint are mandatory: use `http.ServeFile`.
- SALDO multiword lemmas are filtered at import; they balloon `forms`.

## Multi-language

Swedish is the only language wired up, but the schema and API are already
per-language: `language_code` scopes `sources`, `lexemes`, `forms`,
`user_words`. Adding a language means implementing `lexicon.DictionaryProvider`
and `lexicon.MorphologyProvider` and registering them in
`api/internal/lexicon/register.go`. Nothing else should change — do not
reintroduce language-specific assumptions outside those providers.

## Licensing

Personal and private. Do not deploy publicly or redistribute cached audio.
Attribution for Sveriges Radio, 8 Sidor, Folkets lexikon (CC BY-SA 2.5), SALDO
(CC BY-SA 2.5) and KB-Whisper is required and lives on the Settings screen.

## Deployment
There is a local dev instance running here, and is rebuilt after chat completions (via Stop hook). Prod instance uses the image built in Github CI.
