# 12: Remove the old index and update README

**What to build:** The code base contains only the new design: the home-grown parser, call graph and symbol index, and anything else no longer reachable from the tools, are deleted. README describes the new tool set, the Output budget config keys, and that Qdrant/Ollama are needed only for semantic search.

**Blocked by:** 03, 04, 05, 06, 07, 08, 09, 10, 11

**Status:** resolved

- [x] No namespace from the old parser/graph/symbol-index design remains; the full test suite passes.
- [x] `clj-kondo --lint src test` reports no warnings.
- [x] README lists exactly the tools in `tools/list` with their inputs and answer format.
- [x] README documents `:output/limit`, `:output/max-chars` and the optional external services.
