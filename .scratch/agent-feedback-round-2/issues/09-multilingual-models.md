# 09: Multilingual models for semantic search

**What to build:** First write an `^:integration` test: fixtures with Russian docstrings (wrapped, with signatures) and queries in English and Russian, each expecting a Definition in the top 3. It must fail with `vishalraj/nomic-embed-code` + `qllama/bce-reranker-base_v1`. Then measure candidates (embeddings such as `bge-m3` or `multilingual-e5`, reranker `bge-reranker-v2-m3` via `llama-server --rerank`) and pick the pair that passes it together with acceptance case 9 of `agent-usefulness`. Make that pair the default in `config.clj` and `resources/config.edn`. Record the measurement (per-model hits in the top 3, for both test sets) in ADR-0008. Describe the models, their setup and when the code-specific models are the better choice in `README.md` and `README.RU.md`. If no pair passes both sets, stop and report the measurement instead of weakening the test.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] The new integration test is shown to fail with the current models (numbers in ADR-0008).
- [ ] The chosen pair passes it and the existing case 9.
- [ ] Defaults, ADR-0008 and both READMEs updated; the collection is recreated on the model change.
