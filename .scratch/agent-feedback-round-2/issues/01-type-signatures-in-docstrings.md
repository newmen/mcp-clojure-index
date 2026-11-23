# 01: Type signatures in this code base's docstrings

**What to build:** Every `defn`, `defn-` and `defmacro` in `src/` and `test/` (except `deftest`) has a docstring that follows `.claude/rules/docstrings.md`: an English first sentence that stands alone as a Docstring summary, and a Type signature in arrow notation as the last paragraph. Every `def` that has a docstring ends with its type. Every capitalized type is defined with `s/def` (`[clojure.spec.alpha :as s]`) in its owning namespace; types that are `GLOSSARY.md` terms keep the term's name. Existing `;; <Name> shape:` comments stay; the spec states the same keys. Before that, `clojure.string` is re-required as `st` everywhere (it is `s` today in 20 files), so that `s` is free for `clojure.spec.alpha` (`.claude/rules/naming.md`). No behaviour changes.

**Deviation:** `.claude/rules/docstrings.md` lists built-in types that need no spec: `String`, `Long`, `Double`, `Boolean`, `Symbol`, `Keyword`, `UUID`, `Pattern`, `Map`, `Any`, the constructors `Maybe X`, `IO X`, `Atom X`, and JVM classes by their simple name (`File`, `OutputStreamWriter`). "Every capitalized type is defined with `s/def`" holds for every other name.

**Blocked by:** none

**Status:** resolved

- [x] Every `defn`, `defn-` and `defmacro` in `src/` and `test/` has a docstring whose last paragraph is a Type signature.
- [x] Every function with side effects (files, network, Qdrant, Ollama, atoms, logging) has `IO` in its signature; every function without `IO` is pure.
- [x] `clojure.string` is required as `st` and `clojure.spec.alpha` as `s` in every file.
- [x] Every capitalized type name in a signature has an `s/def` in its owning namespace.
- [x] `lein test` passes, and clj-kondo reports no new findings.
