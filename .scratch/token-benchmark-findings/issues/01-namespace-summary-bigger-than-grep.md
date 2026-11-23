# 01: `namespace_summary` is bigger than the careful grep

**Status:** resolved

ADR-0001 and the README say an answer bigger than grep is a bug. ADR-0009 measures `namespace_summary` at −67% against careful grep in this repository (17 of 20 namespaces bigger, median −137%) and −36% in the large project (122 of 181). Careful grep is `rg '^\s*\(def'` in the file plus `rg -F ns`. The summary adds arglists, Type signatures, Docstring summaries, specs and `required by`, and pages through 3000–4000 characters per namespace. Against reading the file it saves 73–81%.

Decide one of:

- shrink the answer, e.g. leave out Type signatures and private Definitions by default, or add a `detail` parameter;
- refine the ADR-0001 principle: compare with what the agent needs to answer the question (reading the file), not with the bare `(def` lines.

Measured 2026-11-23 with `dev/token_benchmark.clj` (ADR-0009).

## Comments

2026-11-23: both options. `namespace_summary` gets `detail` (default false): Definition lines without Type signatures and Docstring summaries unless asked. ADR-0001 now measures "smaller" against the grep and reads that give the same information (for a namespace, reading the file); ADR-0006 and ADR-0009 updated. Remeasured: default +31% (−2%) and −19% (−28%) against careful, 89% and 84% against typical; with `detail` −61% and −40%. The rest of the excess is what `rg '^\s*\(def'` misses (specs, protocol methods, record constructors, requirers).
