---
paths:
  - "src/**"
  - "test/**"
---

# Testing and linting

- One test file per source namespace under `test/mcp/`, `deftest` names in descriptive kebab-case.
- Prefer `(is (= expected actual))` over `(is (thrown? …))`.
- Tests that need Qdrant or Ollama are tagged `^:integration` and are not part of the default run.
- Run `clj-kondo --lint src test` before finishing a change; fix every warning.
