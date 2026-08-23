-- Two more Swedish listening sources.
--
-- Vetenskapsradion Nyheter (SR program 406) is ~5 minutes of daily science
-- news, confirmed against the live API. Like Klartext it is republished per
-- airing, so it opts into the same publish-date dedupe.
--
-- Forskning & Framsteg publishes its podcasts on Acast; the fof.se article
-- feed carries no audio and the site embeds Spotify players, which are not
-- downloadable. The Acast RSS is the real feed. The "Artiklar" show is read
-- magazine articles, ~9 minutes each. Note it has not published since
-- February 2024: it is a 175-episode archive, not a live source.
INSERT INTO sources (slug, name, kind, config, enabled, language_code) VALUES
  ('vetenskap', 'Vetenskapsradion', 'sr_api',
   '{"program_id": 406, "program_name": "Vetenskapsradion Nyheter", "one_per_day": true}'::jsonb,
   TRUE, 'sv'),
  ('fof', 'Forskning & Framsteg', 'rss',
   '{"feed_url": "https://feeds.acast.com/public/shows/62b1b4ff5513320013f3088b"}'::jsonb,
   TRUE, 'sv')
ON CONFLICT (slug) DO NOTHING;

-- How many of the newest items per source are downloaded automatically.
-- Anything older is left re-fetchable on demand rather than filling the disk.
ALTER TABLE sources ADD COLUMN auto_download_limit INT NOT NULL DEFAULT 10;
