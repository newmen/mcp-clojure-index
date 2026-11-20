---
paths:
  - "**/*.clj"
  - "**/*.cljc"
---

# Java interop

- Prefer `clojure.string` over `String` methods: `(st/starts-with? x "a")`, not `(.startsWith x "a")`. Direct interop (`.indexOf`, `.getBytes`) is fine in hot loops, with type hints.
- Every parameter passed to a Java method gets a type hint (`^String`, `^java.io.File`, …).
- Every namespace that uses interop starts with `(set! *warn-on-reflection* true)`; reflection warnings are treated as errors.
