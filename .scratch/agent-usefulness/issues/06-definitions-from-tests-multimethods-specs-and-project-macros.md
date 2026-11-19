# 06: Definitions from tests, multimethods, specs and project macros

**What to build:** Definitions created by `deftest`, `defmulti`/`defmethod`, `s/def` and by the indexed project's own macros are first-class: `find_definition` finds them and `find_usages` names them as the Enclosing definition (so a call from inside a test reads `… call  my.ns-test/some-test`). The server honours the indexed project's `.clj-kondo/config.edn` (`lint-as`, hooks), so a project macro declared there produces Definitions like the core form it imitates.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** resolved

- [x] `deftest`, `defmethod` (with dispatch value), `s/def` (keyword name) and a `lint-as` project macro each yield a Definition.
- [x] The project's `.clj-kondo/config.edn` is picked up when present and ignored gracefully when absent.
- [x] Usages inside these forms report them as Enclosing definition.
- [x] Spec acceptance case 5 passes on fixtures.

## Comments

- `find_usages` (ticket 03) was not merged when this was built. What this ticket provides is `mcp.code-index/enclosing-definition [index file-path row col]`. It returns the innermost Definition whose form contains the position, and `qualified-name` formats it (`app.core/area :circle`, `:app.core/uuid`, `app.core-test/helper-test`). It is tested directly on usages inside deftest, defmethod, s/def, a lint-as macro and a defrecord body (fixture `test-resources/fixtures/definitions_project`). `find_usages` should use it for the Enclosing definition, because clj-kondo's `:from-var` is missing inside defmethod, s/def and record bodies.
- Definition additions: defmethods carry `:dispatch` (dispatch value as written) and `:kind "defmethod"`; specs have a keyword `:name` and `:kind "s/def"`; lint-as forms take their kind from the project macro (`defhandler`). `resolve-name` accepts `area :circle` (one defmethod; a bare `area` denotes only the defmulti) and spec keywords `:ns/k`, `::k`, `::alias/k`.
- The fixture's `.clj-kondo/config.edn` is force-added, because `.gitignore` ignores `.clj-kondo/`.
