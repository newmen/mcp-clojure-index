# 02: `code-index/definitions` is recomputed on every tool call

**Status:** resolved

`mcp.code-index/definitions` runs `file-definitions` over the analysis of every file on each call. On the large project (6535 Definitions) that is ~3 s per call. `dev/token_benchmark.clj` works around it with `(with-redefs [code-index/definitions (memoize ...)])`; the live server pays it on every answer that calls it.

Cache the Definitions per file next to the analysis (they change only when the file's mtime changes) and rebuild only for refreshed files. Then drop the `with-redefs` in the benchmark.
