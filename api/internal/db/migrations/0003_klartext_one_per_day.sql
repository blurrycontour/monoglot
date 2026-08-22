-- Sveriges Radio publishes the same Klartext episode once per airing (19:00
-- and 21:00 on weekdays), each with its own episode id and audio URL but
-- identical content. Deduping on (source_id, external_id) alone therefore
-- lets a second copy of every episode through, which clutters the library
-- and doubles the cost of the slowest pipeline stage.
UPDATE sources
SET config = config || '{"one_per_day": true}'::jsonb
WHERE slug = 'klartext';
