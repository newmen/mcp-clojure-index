# 06: Search exclusion (`:search/exclude`)

**What to build:** A project (or its agent) lists Search exclusions in `:search/exclude`: patterns with the syntax of `:index/exclude`, matched against paths relative to the project root, default `[]`. Files under them are never embedded and never `semantic_search` hits, from either vector or lexical candidates; the precise tools still see them. Adding an exclusion in the Project config removes their points from the next call on; removing it makes them stale so the background sync embeds them. The `semantic_search` description gains one sentence about `:search/exclude` in the Project config. See spec `.scratch/agent-feedback-round-3/spec.md`, sections `semantic_search` and Project config; `GLOSSARY.md` Search exclusion.

**Blocked by:** 04, 05 (reuses the file-set reconciliation of the semantic index)

**Status:** resolved

- [x] `:search/exclude` is a known key with default `[]` and is validated like `:index/exclude`.
- [x] Excluded files are not embedded at sync and never appear in `semantic_search` answers.
- [x] `find_definition`, `find_usages`, `get_source`, `namespace_summary` and `find_implementations` still answer about excluded files.
- [x] Adding an exclusion removes its files' points; removing it makes them stale and the next sync embeds them.
- [x] The `semantic_search` description mentions `:search/exclude` in `.clojure-index.edn`.
- [x] Tool-seam tests (fake search services) and semantic-index-seam tests (fake Qdrant) cover the above.
