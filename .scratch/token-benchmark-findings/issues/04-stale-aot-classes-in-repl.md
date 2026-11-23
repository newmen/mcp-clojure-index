# 04: stale AOT classes in `target/classes` shadow sources in the REPL

**Status:** needs-triage

`project.clj` has a top-level `:aot [mcp.server]` (transitively every namespace) and `:uberjar {:aot :all}`, both compiling into `target/classes`, which is on the REPL classpath. `require` loads a namespace's `__init.class` when it is newer than the `.clj`, so after editing and then building (or with a clock skew) the REPL can run old code; `:reload` does not help for transitive requires. The benchmark session had to `load-file` every source instead.

Options: compile the uberjar into a separate `:compile-path` / `:target-path` per profile (e.g. `:uberjar {:target-path "target/uberjar"}`), or `lein clean` before `lein repl`; document it in CLAUDE.md.
