# 10: `:search/min-relevance` and `no confident matches`

**What to build:** When the reranker runs and the best relevance is below `:search/min-relevance`, `semantic_search` returns the hits after a first line `no confident matches (best 0.49), try rephrasing`. Without the reranker there is no such line. Calibrate the default between the best score of queries that have no matching Search unit and the worst score of the expected hits in ticket 09's test, using the chosen models. Document it in both READMEs.

**Blocked by:** 09

**Status:** ready-for-agent

- [ ] `^:integration`: queries with no matching unit get the line, and the queries of ticket 09 don't (acceptance case 13).
- [ ] Without the reranker the line never appears (default `lein test`).
- [ ] The chosen threshold and the scores behind it are recorded in ADR-0008.
