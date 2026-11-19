# 06: Definitions from tests, multimethods, specs and project macros

**What to build:** Definitions created by `deftest`, `defmulti`/`defmethod`, `s/def` and by the indexed project's own macros are first-class: `find_definition` finds them and `find_usages` names them as the Enclosing definition (so a call from inside a test reads `… call  my.ns-test/some-test`). The server honours the indexed project's `.clj-kondo/config.edn` (`lint-as`, hooks), so a project macro declared there produces Definitions like the core form it imitates.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** ready-for-agent

- [ ] `deftest`, `defmethod` (with dispatch value), `s/def` (keyword name) and a `lint-as` project macro each yield a Definition.
- [ ] The project's `.clj-kondo/config.edn` is picked up when present and ignored gracefully when absent.
- [ ] Usages inside these forms report them as Enclosing definition.
- [ ] Spec acceptance case 5 passes on fixtures.
