# Not building

Decided against, with the reasoning, so it stays decided. Everything else that
matters lives in the code, `README.md` or `CLAUDE.md`; this file is only the
list of roads not taken.

| Not building | Why |
|---|---|
| Spaced repetition system | Anki exists and is better. Export instead. |
| Pronunciation scoring | Whisper is trained to be accent-robust; it will transcribe mispronounced Swedish as the correct word. Real scoring needs phoneme-level GOP against an acoustic model. Different project. |
| Speech input of any kind | Not the bottleneck for listening comprehension. |
| Content recommendation / embeddings / vector search | A handful of hand-picked feeds covers it. This is the most seductive and least necessary component. |
| Multi-user, accounts, registration | Single user, private instance. |
| Grammar explanations, exercises, quizzes | Not the bottleneck. |

Adding a *source* was on this list originally ("two sources, prove the loop
first") and has since been done deliberately, five times. The rest stands.

If something not listed here starts to look necessary, that is a decision to
make explicitly, not to drift into.
