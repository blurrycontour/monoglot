-- Vetenskapsradion (programme 412): one 19-minute science story per weekday.
--
-- Distinct from Vetenskapsradion Nyheter (406), already a source at 5 minutes:
-- 412 is the long-form daily programme, one subject per episode. The other
-- strands in that family are not options — Hälsa and Forskarliv both stopped
-- in October 2024, veckomagasin in 2020, and Historia runs to 45 minutes.
--
-- one_per_day: SR republishes the same episode for each airing, so the
-- (source_id, external_id) key alone lets a second copy of every episode
-- through.
-- The existing source is programme 406, which SR calls Vetenskapsradion
-- Nyheter. It was named just "Vetenskapsradion", which is the name of the
-- programme being added here; two chips reading the same thing would be worse
-- than a long label.
UPDATE sources SET name = 'Vetenskapsradion Nyheter' WHERE slug = 'vetenskap';

INSERT INTO sources (slug, name, kind, config, enabled, language_code, auto_download_limit)
VALUES ('vetenskap_daglig', 'Vetenskapsradion', 'sr_api',
        '{"program_id": 412, "program_name": "Vetenskapsradion", "one_per_day": true}',
        1, 'sv', 10)
ON CONFLICT (slug) DO NOTHING;
