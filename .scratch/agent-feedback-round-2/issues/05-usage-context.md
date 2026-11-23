# 05: Usage context in `find_usages`

**What to build:** Each `find_usages` line ends with up to 80 characters of its source line around the Usage column, with whitespace runs collapsed, for every Usage kind, including `keyword` and `edn`. `context: false` turns it off. The tool description mentions it, and ADR-0006 already records it.

**Blocked by:** 02

**Status:** resolved

- [x] `call`, `keyword` and `edn` Usages carry their context. A long line is cut around the column, not at its start.
- [x] `context: false` gives the previous line format.
- [x] Answers stay within the budget, and paging still names the target on each page (acceptance case 8).
