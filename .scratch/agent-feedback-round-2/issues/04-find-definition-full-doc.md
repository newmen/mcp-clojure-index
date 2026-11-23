# 04: `find_definition doc: "full"`

**What to build:** `find_definition` accepts `doc: "summary" | "full"` (default `"summary"`). With `"full"`, every Definition found, including each ambiguity candidate, is a line `path:line  kind  ns/name  [arglists]` followed by its whole docstring (signature included), indented by two spaces with its line breaks, or `  (no docstring)`. A finding is one Definition with its docstring, and `limit`/`offset` count Definitions. A single finding longer than the budget is cut and ends with `truncated: docstring shown A of B chars`. `get_source` gets the same kind of note: `source shown A of B chars`. See the spec table.

**Blocked by:** 03

**Status:** resolved

- [x] One Definition, ambiguous candidates and a Definition without a docstring give the answers in the spec table.
- [x] Paging with `offset` works over full-doc findings.
- [x] A docstring longer than the budget ends with the docstring Truncation note; a long `get_source` body ends with the source one.
- [x] Acceptance case 7.
