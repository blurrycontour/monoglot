-- Time actually spent listening, by day.
--
-- Not derivable from anything already stored. `progress` records how far into
-- an episode you got, which counts a sentence heard five times exactly once and
-- counts a re-listen not at all — and those are the minutes this is for. The
-- clock is the client's: it accumulates wall time while audio is playing and
-- flushes it here, so a rewind costs what it really costs and a slower playback
-- speed shows up as the longer sitting it is.
--
-- One row per local day, keyed by the phone's own date so a session at 23:55
-- lands on the day it felt like rather than the day UTC says.
CREATE TABLE listening (
  day        TEXT PRIMARY KEY,
  ms         INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now'))
);
