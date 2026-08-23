-- Klartext program id 493 and Vetenskapsradion Nyheter 406 confirmed against
-- the live SR API. Both are republished per airing, so they dedupe on publish
-- date as well as episode id.
--
-- 8 Sidor per-article audio is ReadSpeaker TTS, so the human-read daily
-- podcast is ingested instead. Forskning & Framsteg publishes on Acast; its
-- own feed carries no audio and the site embeds Spotify players.
INSERT INTO sources (slug, name, kind, config, enabled, language_code) VALUES
  ('klartext', 'SR Klartext', 'sr_api',
   '{"program_id": 493, "program_name": "Klartext", "one_per_day": true}', 1, 'sv'),
  ('8sidor', '8 Sidor', 'rss',
   '{"feed_url": "https://8sidor.se/feed/podcast/"}', 1, 'sv'),
  ('vetenskap', 'Vetenskapsradion', 'sr_api',
   '{"program_id": 406, "program_name": "Vetenskapsradion Nyheter", "one_per_day": true}', 1, 'sv'),
  ('fof', 'Forskning & Framsteg', 'rss',
   '{"feed_url": "https://feeds.acast.com/public/shows/62b1b4ff5513320013f3088b"}', 1, 'sv');
