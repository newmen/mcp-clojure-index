# 01: Tell the agent how to word a `semantic_search` query

**What to build:** An agent whose English query misses Russian docstrings gets told how to rephrase. The `semantic_search` description gains one sentence: describe what the code does in the words its docstrings would use, not the identifiers you expect; a query in the docstrings' language matches best. The `no confident matches` line ends with `try rephrasing in the docstrings' words`. When more than half of the searched Descriptions are mostly in one non-Latin script, it also says `(they are mostly in <Script>)`. See spec `.scratch/agent-feedback-round-4/spec.md`, section "Query wording hints".

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The `semantic_search` description carries the wording sentence. The server's `initialize` instructions are unchanged.
- [ ] The confidence line reads `no confident matches (best X), try rephrasing in the docstrings' words`.
- [ ] On a project whose Descriptions are mostly Cyrillic the line ends with ` (they are mostly in Cyrillic)`. On a Latin-script project it does not.
- [ ] Script detection runs only when the line is printed.
- [ ] Tool-seam tests: both fixtures (`multilingual_project` and an English one), best hit below and above the threshold.
