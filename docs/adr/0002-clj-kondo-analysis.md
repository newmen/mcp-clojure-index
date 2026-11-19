# Code analysis via clj-kondo instead of a home-grown parser

The first version parsed code itself with rewrite-clj and string matching. It resolved aliases with a substring check on the `ns` form, did not support `:refer` or `#'var`, did not recognise `deftest`, `defmethod`, `s/def` or project macros (so calls from tests were invisible), collected no keywords, and resolved unknown names to the first definition with the same simple name, which produced false positives. We use clj-kondo's analysis output (embedded as a library, in-process) as the only source of Definitions and Usages. It already handles all of the above, honours the indexed project's `.clj-kondo/config.edn` (`lint-as`, hooks), analyses EDN files (keywords with positions) and caches results. It is the same foundation clojure-lsp uses.

## Considered Options

- **Fix the rewrite-clj parser.** Rejected: re-implementing name resolution correctly is months of work and would always lag behind clj-kondo.
- **Run clj-kondo as a subprocess.** Rejected: process start per re-analysis and EDN parsing of stdout cost more than a library call, and the binary may be missing.
