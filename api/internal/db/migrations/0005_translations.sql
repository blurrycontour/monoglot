-- Cache for the machine-translation fallback.
--
-- Folkets covers everyday Swedish well but has real gaps, and a compound it
-- has never seen ("smittspridningen") leaves a tap with nothing at all. The
-- fallback is a last resort, so it is cached permanently: the same word must
-- never cost a second network call, and the tap latency budget does not allow
-- one at all on a repeat.
CREATE TABLE IF NOT EXISTS translations (
    language_code TEXT NOT NULL,
    surface       TEXT NOT NULL,
    translation   TEXT NOT NULL,
    provider      TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
    PRIMARY KEY (language_code, surface)
);
