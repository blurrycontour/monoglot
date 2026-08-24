-- How many times transcription has been tried for an item.
--
-- Without this a single failure was permanent: the item sat in 'failed' and
-- nothing ever picked it up again, so a worker that was briefly out of memory
-- cost that episode for good. The pipeline retries a bounded number of times
-- and only then leaves it alone.
ALTER TABLE items ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
