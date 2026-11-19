# 03: `find_usages` of Definitions

**What to build:** An agent asks who uses one or several Definitions in a single call and gets every Usage, grouped by target, one line each: `path:line:col  usage-kind  ns/enclosing-definition`. Usages are found through any alias, `:refer`, `#'var` and when the function is passed as a value (`partial`, `comp`, `map` argument). Kinds in this ticket: `call`, `value`, `var-ref`, `require` (the namespace appears in `:require`). The server determines Test paths (from `project.clj` `:test-paths`, or `deps.edn` alias `:extra-paths` for aliases containing `test`; fallback `test/` and `*_test` files), and `include_tests` (default true) filters test Usages out. `kinds` filters by Usage kind. An empty result says what was searched (`no usages of X (checked 214 files, including tests)`). The tool description says explicitly that it is more complete than `grep` and how large the answer is. Old `find_callers` and `find_callees` are removed.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** resolved

- [x] Usages through an alias, `:refer`, `#'var` and value passing are all found and carry the right kind.
- [x] Several targets in one call produce one group per target.
- [x] Each line names the Enclosing definition.
- [x] Test paths are detected from project files with the documented fallback; `include_tests false` drops test Usages.
- [x] `kinds` filters the result.
- [x] Empty results report files checked and whether tests were included.
- [x] `find_callers` and `find_callees` no longer appear in `tools/list`.
- [x] Acceptance tests (spec cases 1 without `redef`/`in-comment`, and 2) on this code base plus fixtures where needed, asserting completeness against a hand-checked list and size within the budget.
