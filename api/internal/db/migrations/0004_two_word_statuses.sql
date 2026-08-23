-- Vocabulary has two states, not three.
--
-- A word you tapped is by definition one you did not know, so "unknown" and
-- "learning" were never distinct in practice: the third state only added a
-- decision at every lookup. Anything previously unknown becomes learning.
UPDATE user_words SET status = 'learning' WHERE status NOT IN ('known', 'learning');
