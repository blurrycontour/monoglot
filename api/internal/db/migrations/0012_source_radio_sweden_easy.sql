-- Radio Sweden på lätt svenska (programme 4916): one easy-Swedish news
-- episode each weekday. Keep date deduplication enabled in case SR republishes
-- an episode under a new id, as it does for some other daily programmes.
INSERT INTO sources (slug, name, kind, config, enabled, language_code, auto_download_limit)
VALUES ('radio_sweden_latt', 'SR Lätt Svenska', 'sr_api',
        '{"program_id": 4916, "program_name": "Radio Sweden på lätt svenska", "one_per_day": true}',
        1, 'sv', 10)
ON CONFLICT (slug) DO NOTHING;
