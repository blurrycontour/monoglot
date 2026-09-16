-- Per-episode pipeline metadata, surfaced in the app's episode options.
--
-- All nullable: episodes transcribed before this migration have none of it, and
-- the app shows a dash rather than a zero for anything missing. Nothing in the
-- pipeline depends on these — they are a record of how an episode was made, not
-- an input to making it.
ALTER TABLE items ADD COLUMN transcribed_at   TEXT;
ALTER TABLE items ADD COLUMN transcribe_model TEXT;
ALTER TABLE items ADD COLUMN download_ms      INTEGER;
ALTER TABLE items ADD COLUMN transcribe_ms    INTEGER;
ALTER TABLE items ADD COLUMN audio_bytes      INTEGER;
