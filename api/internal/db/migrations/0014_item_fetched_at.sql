-- When an episode was first fetched: downloaded and transcribed to 'ready'.
--
-- "Remove from server" archives an item to free disk, but a reader still wants
-- it where it was, in its own date section, marked as re-fetchable — not buried
-- in the back catalogue of episodes that were never fetched at all. The only
-- thing that tells those two archived states apart is whether the item was ever
-- ready, so that fact has to outlive the archive. It is set once and never
-- cleared.
ALTER TABLE items ADD COLUMN fetched_at TEXT;

-- Backfill: anything ready now has been fetched, and any archived item with a
-- progress row was listened to, so it was fetched too. Older archived items
-- that were never touched stay NULL and remain in the back catalogue.
UPDATE items SET fetched_at = created_at WHERE status = 'ready';
UPDATE items SET fetched_at = created_at
 WHERE status = 'archived'
   AND id IN (SELECT item_id FROM progress);
