# 03: `no confident matches` below 0.3

**What to build:** `semantic_search` starts with `no confident matches` whenever the best hit scores below 0.3 (was 0.05), still listing the hits after it, so an agent rephrases a weak query instead of opening random hits. See spec `.scratch/agent-feedback-round-3/spec.md`, section `semantic_search`.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] The default of `:search/min-relevance` is 0.3, both in the built-in defaults and in the shipped server config.
- [x] A tool-seam test (fake search services) shows the warning for a best hit of, e.g., 0.15 and none for 0.95; hits are listed either way.
- [x] Config tests cover the new default.
- [x] ADR-0007 mentions the 0.3 default if it states the threshold.
