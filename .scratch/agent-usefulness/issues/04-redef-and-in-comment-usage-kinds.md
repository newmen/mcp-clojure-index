# 04: `redef` and `in-comment` Usage kinds

**What to build:** `find_usages` distinguishes a Definition redefined in `with-redefs` (kind `redef`, typically in tests) and a Usage inside a `comment` form (kind `in-comment`), so an agent changing a function's contract sees the test doubles it must update and can filter out REPL scratch code. Start by verifying against clj-kondo's analysis output how these two kinds can be derived (e.g. from the enclosing form, call position, or the bindings vector of `with-redefs`); record the finding in this ticket's comments before implementing.

**Blocked by:** 03 (`find_usages` of Definitions)

**Status:** resolved

- [x] Findings on how clj-kondo exposes these cases are recorded under `## Comments` here.
- [x] A Definition bound in `with-redefs` is reported with kind `redef`, with the test as Enclosing definition.
- [x] A Usage inside `comment` is reported with kind `in-comment`; `kinds` can exclude it.
- [x] Spec acceptance case 1 passes in full, including both kinds, on fixtures.

## Comments

**Findings (clj-kondo 2026.10.26, re-checked on `test-resources/usages/lein_project`):** clj-kondo has no flag for either case; both are derived from form ranges, since every macro call is itself a var-usage carrying the whole form's range (`:row :col :end-row :end-col`, `:end-col` exclusive).

- `in-comment`: `(comment (get-uuid 99))` gives a `get-uuid` usage with `:arity 1` and **no `:from-var`**, plus a `{:to clojure.core :name comment :row 16 :col 1 :end-row 17 :end-col 17}` usage. A Usage is `in-comment` when its symbol position lies inside the range of a `clojure.core/comment` usage of the same file. It is checked before `redef`/`var-ref`/`call`, so `kinds` can exclude it. The Enclosing definition falls back to the namespace.
- `redef`: in `(deftest get-uuid-test (with-redefs [core/get-uuid (fn ...)] ...))` the binding key is an ordinary usage without `:arity` (`:name-row 9 :name-col 17`, `:from-var get-uuid-test`), indistinguishable from `value` by its own keys. clj-kondo gives the `clojure.core/with-redefs` form range (9:3–10:18) but not binding positions, so the binding-key positions of each enclosing `with-redefs` are read with rewrite-clj (every second child of the bindings vector, `#'x` keys unwrapped); a usage at one of them is `redef`. The value expressions and body are classified as usual.
- Pitfall seen while building the fixture: a `clojure.test/deftest` written fully qualified **without requiring `clojure.test`** is not recognised, so its body has no `:from-var`; the fixture requires `clojure.test`.

Full notes: verified clj-kondo notes, §4 steps 3–4.
