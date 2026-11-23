# 07: Usage warning: unused require and unused refer

**What to build:** In `find_usages` of a namespace target, a `require` Usage ends with `unused` when neither its alias nor any referred name is used in that file, or with `unused refer: b, c` when only those referred names are unused. A use is anything resolving to the namespace: a call or value through the alias, a referred name, `#'`, `::alias/kw` (the only use an `:as-alias` can have), or a fully qualified name. Bare requires and `:refer :all` are never marked. These are Usage warnings, so `warnings_only` selects them. The description says `unused` is a hint.

**Blocked by:** 06

**Status:** resolved

- [x] Fixtures: unused alias, partly unused `:refer`, use only through `::alias/kw` (not marked), bare require, `:refer :all` (neither marked).
- [x] Acceptance case 10.
