# 11: Semantic search on Search units, Qdrant and Ollama optional

**What to build:** `semantic_search` searches Search units built from the precise index: every Definition (including tests, multimethods, specs and project macros) and every top-level entry of an EDN map. Embedding text is `ns/name` + arglists + docstring + source truncated to about 2000 characters; the reranker stays. Embeddings of changed files are recomputed in the background after the freshness check; until they are ready, the answer ends with `embeddings stale for N files`. If Qdrant or Ollama is unavailable, `semantic_search` answers that it is unavailable and every other tool keeps working (ADR-0003). Old collections are dropped and recreated with the new payload; the Java WatchService watcher and the graph restore from Qdrant are removed.

**Blocked by:** 01 (Output budget and compact semantic search), 06 (Definitions from tests, multimethods, specs and project macros), 10 (Freshness check before each answer)

**Status:** resolved

- [x] Search units cover all Definitions and top-level EDN map entries.
- [x] Answers keep the compact line format from ticket 01 and fit the Output budget.
- [x] Stale embeddings are reported with the count of affected files.
- [x] With Qdrant or Ollama down, `semantic_search` reports unavailability and precise tools answer (spec acceptance case 8).
- [x] The watcher and restore-from-Qdrant code are gone; an old-format collection is recreated on start.
- [x] `^:integration` test (spec acceptance case 9): for a fixed set of queries against fixtures, the expected Definition is in the top 3. If it fails, open a follow-up ticket for lexical matching with rank fusion instead of tuning ad hoc.

Integration result (2026-11-19, local Qdrant + Ollama): 4 of 8 queries miss the top 3. Follow-up: `13-lexical-matching-with-rank-fusion.md`.
