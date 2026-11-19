---
paths:
  - "**/*.clj"
  - "**/*.cljc"
---

# Clojure idioms

- `(s/blank? x)`, not `(= x "")`.
- Use `->` / `->>` for pipelines instead of nested calls.
- Compose predicates with `comp`: `(remove (comp #{:whitespace :newline} n/tag) xs)`, not an anonymous `fn`.
- Use `when` instead of `if` with an empty-collection or `nil` else branch; don't allocate `[]` just to return "nothing".
- Express fallback chains with `or`, not nested `if-let`; don't wrap in `or` a value that already has a fallback.
- Remove conditionals whose branches are identical, and wrappers that only forward to another function.
- Extract a `defn-` helper as soon as the same computation appears twice.
