# 04: `redef` and `in-comment` Usage kinds

**What to build:** `find_usages` distinguishes a Definition redefined in `with-redefs` (kind `redef`, typically in tests) and a Usage inside a `comment` form (kind `in-comment`), so an agent changing a function's contract sees the test doubles it must update and can filter out REPL scratch code. Start by verifying against clj-kondo's analysis output how these two kinds can be derived (e.g. from the enclosing form, call position, or the bindings vector of `with-redefs`); record the finding in this ticket's comments before implementing.

**Blocked by:** 03 (`find_usages` of Definitions)

**Status:** ready-for-agent

- [ ] Findings on how clj-kondo exposes these cases are recorded under `## Comments` here.
- [ ] A Definition bound in `with-redefs` is reported with kind `redef`, with the test as Enclosing definition.
- [ ] A Usage inside `comment` is reported with kind `in-comment`; `kinds` can exclude it.
- [ ] Spec acceptance case 1 passes in full, including both kinds, on fixtures.
