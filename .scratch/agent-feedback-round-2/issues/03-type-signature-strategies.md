# 03: Type signature strategies

**What to build:** `:doc/signature` selects a built-in strategy, `{:strategy :last-paragraph}` by default. `:last-paragraph` takes the last paragraph of a docstring with at least two paragraphs, its lines joined. `{:strategy :pattern :regex "..."}` takes the first regex group over the whole docstring. A missing key or `nil` turns signatures off. Strategies are a multimethod. Namespaces have no signature. The signature is a field after the arglists in `find_definition`, `namespace_summary` and the ambiguity candidates of `get_source` and `find_implementations`, but not in `find_usages` or `semantic_search`. The signature paragraph is excluded from the Docstring summary and its `(+N chars)`.

**Blocked by:** 02

**Status:** resolved

- [x] A Russian fixture with a signature paragraph shows the signature as its own field.
- [x] A `def` whose signature is a bare type (no `->`) gets it.
- [x] A one-paragraph docstring has no signature, and its paragraph is the summary.
- [x] `:pattern` works, and a config without `:doc/signature` shows no signatures.
- [x] `find_usages` candidates and `semantic_search` lines carry no signature.
- [x] Acceptance cases 1–3 and 6; this code base's own docstrings (ticket 01) are used as a fixture too.
