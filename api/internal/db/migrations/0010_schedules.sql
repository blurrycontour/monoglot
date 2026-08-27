-- When the pipeline runs unattended.
--
-- Previously two environment variables read once at boot, which meant a change
-- was a file edit and a restart on a machine that otherwise never needs either.
-- A row per time of day instead, editable from the app: a fresh server starts
-- with none and never ingests on its own until somebody asks it to, which is
-- the honest default for a personal server.
--
-- Local time, in the TZ the container runs in — the same clock the times were
-- typed against. Storing UTC would silently shift every schedule twice a year.
CREATE TABLE schedules (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  hour       INTEGER NOT NULL CHECK (hour BETWEEN 0 AND 23),
  minute     INTEGER NOT NULL CHECK (minute BETWEEN 0 AND 59),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
  UNIQUE (hour, minute)
);
