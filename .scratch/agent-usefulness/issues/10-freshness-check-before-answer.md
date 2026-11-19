# 10: Freshness check before each answer

**What to build:** An agent that edits a file and immediately asks again gets an answer that reflects the edit (ADR-0005). Before answering, every precise tool compares file modification times across the project and synchronously re-analyses changed, added and deleted files with clj-kondo.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** ready-for-agent

- [ ] Edit, add and delete of a file are reflected in the very next precise-tool answer (spec acceptance case 7).
- [ ] Only changed files are re-analysed.
- [ ] The check adds negligible latency on this code base (measured in a test).
