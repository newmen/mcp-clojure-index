# 09: `find_implementations`

**What to build:** An agent asks for the Implementations of a protocol or a multimethod and gets one line per Implementation: `path:line  ns/type-or-dispatch-value  method`. Covers `defrecord`/`deftype`/`extend-type`/`extend-protocol`/`reify` for protocols and `defmethod` for multimethods. Old `find_protocol`, `find_record` and `find_macro` (which never returned protocol or record methods) are removed.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** resolved

- [x] Protocol Implementations from records, types, `extend-*` and `reify` are listed with the implementing type and method.
- [x] Multimethod Implementations are listed with their dispatch value.
- [x] `find_protocol`, `find_record` and `find_macro` no longer appear in `tools/list`.
- [x] Tested on fixtures covering each form.
