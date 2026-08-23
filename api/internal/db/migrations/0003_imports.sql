-- Records which bootstrap imports have completed.
--
-- Previously the skip check just counted rows in the target table, but Folkets
-- contributes inflections to `forms` as well as entries to `lexemes`. On a
-- fresh install import-dictionary would populate `forms`, and import-morphology
-- would then see a non-empty table and skip SALDO permanently.
CREATE TABLE imports (
  kind          TEXT NOT NULL,
  language_code TEXT NOT NULL,
  row_count     INTEGER NOT NULL DEFAULT 0,
  completed_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
  PRIMARY KEY (kind, language_code)
);
