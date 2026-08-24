# Monoglot

Personal, single-user Swedish listening-comprehension app, self-hosted in a
homelab. `SPEC.md` defines what is deliberately out of scope — read it before
changing behaviour.

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
./bootstrap.sh                              # checkout -> running instance
./scripts/android.sh                        # signed release APK -> data/apk/
docker compose run --rm api ingest discover # or download | transcribe | all
cd api && GOWORK=off go test ./...
```

`docker-compose.yml` is the production file: it pulls `ghcr.io/blurrycontour/
monoglot-{api,worker}` into a named volume, and the API imports the dictionary
and word forms itself on first start, so a bare host needs only that file and a
`.env`. `docker-compose.override.yml` is merged in on a checkout and switches
to building from source with `./data` bind-mounted.

`GOWORK=off` is mandatory: a parent `go.work` at `~/repos/go.work` otherwise
captures this module.

`applicationId` is `io.blurrycontour.monoglot`; the Kotlin package is still
`se.svenska.trainer` (internal, not worth renaming). Changing `applicationId`
again means a fresh install, not an update: local settings and downloads go.

## Traps

- **Milliseconds as integers everywhere.** Floats cause off-by-one highlight
  bugs. Seconds→ms conversion happens once, at the worker boundary.
- **Transcripts come from the audio, never the publisher's manuscript.**
  Klartext's published script does not match the read audio.
- **The pipeline is a state machine over `items.status`**
  (`new → downloading → downloaded → transcribing → ready`, plus `archived`).
  Every stage must be independently retryable: transcription is slow and must
  never be redone because a later stage failed.
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
- **Disk is tight**; `docker builder prune -af` reclaims the most. Always check
  build exit status — a failure piped through `tail` silently keeps the old
  image running.
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
