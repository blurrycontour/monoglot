-- Klartext program id 493 confirmed against the live SR API
-- (/programs/index -> "Klartext – nyheter på ett enklare sätt").
-- Stored in config rather than hardcoded in Go so it can be corrected
-- without a rebuild if SR renumbers.
INSERT INTO sources (slug, name, kind, config, enabled) VALUES
  ('klartext', 'SR Klartext', 'sr_api',
   '{"program_id": 493, "program_name": "Klartext"}'::jsonb, TRUE),
  -- 8 Sidor per-article audio is ReadSpeaker TTS (synthetic, JS-gated), so
  -- per the spec fallback we ingest the human-read daily podcast instead.
  ('8sidor', '8 Sidor', 'rss',
   '{"feed_url": "https://8sidor.se/feed/podcast/"}'::jsonb, TRUE)
ON CONFLICT (slug) DO NOTHING;
