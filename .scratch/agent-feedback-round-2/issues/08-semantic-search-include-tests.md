# 08: `semantic_search include_tests`

**What to build:** `semantic_search` accepts `include_tests` (default `false`). When it is false, Search units in Test paths are dropped before ranking, and the empty answer says `excluding tests`. The tool description says so.

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] Test Definitions are absent by default and present with `include_tests: true`.
- [ ] Acceptance case 11; runs without Qdrant/Ollama by stubbing the search the way existing semantic tests do.
