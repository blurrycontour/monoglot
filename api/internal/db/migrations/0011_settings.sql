-- Server-side settings: values that belong to the instance rather than to the
-- deployment, and whose whole point is being changed without editing a file
-- and restarting. The schedule got its own table because a schedule is a list;
-- this is for the single values.
CREATE TABLE settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now'))
);
