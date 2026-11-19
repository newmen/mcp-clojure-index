# 01: Output budget and compact semantic search

**What to build:** Every tool answer obeys a server-wide Output budget (ADR-0004), and the existing `semantic_search` returns a Compact answer (ADR-0006) instead of hundreds of thousands of characters. The server owner sets `:output/limit` (default 50 findings) and `:output/max-chars` (default 2000) in `config.edn`; a tool's `limit` argument can only lower them, `offset` pages through the rest, and an answer cut by the budget ends with a Truncation note (`truncated: shown 37 of 312, pass offset=37 or narrow the query`). `semantic_search` returns one line per hit (`score  path:line  kind  ns/name  first docstring line`), paths relative to the project root, top-N only: no source bodies, no expansion with same-file definitions or graph neighbours, no ids or hashes. This is quick relief for the worst symptom; the search itself is rebuilt in ticket 11.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `:output/limit` and `:output/max-chars` are read from config with defaults 50 and 2000; values above the ceiling passed by a tool call are clamped to it.
- [x] An answer exceeding the budget is cut at a line boundary and ends with a Truncation note naming shown/total counts and the next `offset`.
- [x] `offset` returns the next page without repeating findings.
- [x] `semantic_search` accepts `limit`, returns one line per hit in the format above, never includes source bodies, and never adds expansion results.
- [x] Paths in answers are relative to the project root.
- [x] Tests cover clamping, truncation note, paging and the `semantic_search` line format (with Qdrant/Ollama stubbed).
