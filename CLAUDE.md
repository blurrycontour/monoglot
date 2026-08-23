# Monoglot

Personal, single-user Swedish listening-comprehension app. Self-hosted in a
homelab. Read `SPEC.md` before changing behaviour — it defines what is
deliberately out of scope.

## The one idea

Audio is primary; the transcript is a crutch revealed on demand. The user
listens, fails to parse a sentence, reveals just that sentence, taps the word
they missed, and re-listens. Everything in the design serves that loop.

Two consequences that are easy to break:
- **Transcript defaults to hidden.** Never default to full text because it demos better.
- **Tap-to-define must feel instant.** Definitions are resolved at ingestion and
  inlined into the item bundle, so a tap never waits on the network.

## Layout

```
api/       Go 1.26 — chi, pgx. HTTP API, ingestion pipeline, cron.
worker/    Python — faster-whisper + KB-Whisper. Transcription only.
android/   Kotlin, Jetpack Compose, Media3, Room. The only client.
scripts/   android.sh builds the APK inside Docker.
```

## Commands

```bash
./bootstrap.sh                              # clean checkout -> running instance
./scripts/android.sh                        # signed release APK -> data/apk/
docker compose logs -f api worker
docker compose run --rm api ingest discover # or download | transcribe | all
cd api && GOWORK=off go test ./...          # GOWORK=off is required, see below
```

`GOWORK=off` is mandatory for Go commands: a parent `go.work` at
`~/repos/go.work` otherwise captures this module.

The Android `applicationId` is still `se.svenska.trainer`. Changing it would
make Android treat the app as a different install, breaking in-place updates
and discarding downloads and settings. The user-visible branding is Monoglot.

## Non-obvious constraints

- **Milliseconds as integers everywhere.** Float timestamps cause off-by-one
  highlight bugs. Seconds→ms conversion happens once, at the worker boundary.
- **Transcripts come from the audio, never the publisher's manuscript.** Klartext
  publishes a script that does not match the read audio; generating from audio
  makes alignment correct by construction.
- **The pipeline is a state machine over `items.status`**
  (`new → downloading → downloaded → transcribing → ready`, plus `archived`).
  Every stage must be independently retryable — transcription is slow and must
  never be redone because a later stage failed.
- **SR republishes each Klartext episode per airing** with a different
  `external_id` but identical content. Sources set `"one_per_day": true` in
  their config to dedupe by publish date as well.
- **SALDO multiword lemmas are filtered at import.** They balloon `forms` and are
  noise for single-token lookup.
- **Range requests on the audio endpoint are mandatory.** Use `http.ServeFile`.
- **Never pipe a command into `head` under `set -o pipefail`.** The early close
  sends SIGPIPE upstream and fails the script even though the read succeeded.
  This has now caused two real bugs: a truncated SALDO import and a corrupt
  APK version manifest.
- **`scripts/android.sh` publishes only on release tasks**, and reads the
  version back out of the built APK. Publishing a stale APK under a fresh
  version number makes the in-app updater loop forever.
- **Disk is tight.** Whisper models, Postgres, and the Gradle cache all compete.
  `docker builder prune -af` reclaims the most. A failing build that gets piped
  through `tail` will silently keep the old image running — always check exit
  status.

## Multi-language

Swedish is the only language wired up, but the schema and API are already
per-language. `language_code` scopes `sources`, `lexemes`, `forms` and
`user_words`; `languages` holds the ASR hint.

Adding a language means implementing `lexicon.DictionaryProvider` and
`lexicon.MorphologyProvider` and registering them in
`api/internal/lexicon/register.go`. Nothing else should need to change. Do not
reintroduce language-specific assumptions outside those providers.

## Licensing

Personal and private. Do not deploy publicly or redistribute cached audio.
Attribution for Sveriges Radio, 8 Sidor, Folkets lexikon (CC BY-SA 2.5), SALDO
(CC BY-SA 2.5), and KB-Whisper is required and lives on the Settings screen.
