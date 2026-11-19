---
paths:
  - "**/*.clj"
  - "**/*.cljc"
---

# Naming

- Descriptive parameter names (`form-str`, `file-path`), not single letters. Exception: `acc`, `k`, `v`, `x` in one-line `reduce`/`fn` bodies.
- A name must say what the value is. Don't call a parameter `ns-name` if it is not a namespace name.
- Unused bindings get a meaningful `_`-prefixed name (`_file`, not `_`). A parameter accepted but not used yet gets a `;; TODO` explaining why.
- Use the vocabulary of `GLOSSARY.md` (Definition, Usage, Search unit, …) in names, docstrings and test names.
- Standard aliases: `clojure.string` → `s`, `clojure.data.json` → `json`, `clj-http.client` → `http`.
