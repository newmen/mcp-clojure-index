# 06: Usage warning: wrong arity, `warnings_only`

**What to build:** A `call` Usage whose argument count matches no arity of its target ends with `wrong arity: N args, takes 1|2` (`takes 2+` for varargs), using clj-kondo's usage arity and the definition's fixed and varargs arities. `find_usages` accepts `warnings_only: true`, which keeps only Usages that carry a Usage warning. The tool description explains both.

**Blocked by:** 05

**Status:** resolved

- [x] Wrong arity is marked for fixed and varargs targets; correct calls, `value` and `var-ref` Usages are not.
- [x] `warnings_only` returns only marked Usages and reports an empty result as usual.
- [x] Acceptance case 9.
