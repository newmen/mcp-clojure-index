---
paths:
  - "**/*.clj"
  - "**/*.cljc"
---

# Namespace structure

- Private by default: anything that is not part of the namespace's API is `defn-` / `^:private`.
- Public `defn`s have docstrings.
- `^:private` constants live at the top of the namespace, not inline in `let`.
- When a namespace works with a non-trivial map shape, document the shape in a block comment above the functions that use it.
- Delimit logical groups with section comments:
  `;; ---------------------------------------------------------------------------`
