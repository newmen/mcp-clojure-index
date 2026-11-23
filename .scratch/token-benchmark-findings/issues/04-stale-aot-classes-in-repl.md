# 04: stale AOT classes in `target/classes` shadow sources in the REPL

**Status:** resolved

`project.clj` has a top-level `:aot [mcp.server]` (transitively every namespace) and `:uberjar {:aot :all}`, both compiling into `target/classes`, which is on the REPL classpath. `require` loads a namespace's `__init.class` when it is newer than the `.clj`, so after editing and then building (or with a clock skew) the REPL can run old code; `:reload` does not help for transitive requires. The benchmark session had to `load-file` every source instead.

Options: compile the uberjar into a separate `:compile-path` / `:target-path` per profile (e.g. `:uberjar {:target-path "target/uberjar"}`), or `lein clean` before `lein repl`; document it in CLAUDE.md.

## Comments

Implemented. The top-level `:aot [mcp.server]` is gone (Leiningen compiled it into `target/classes` before every `repl`, `run` and `test`); only the `:uberjar` profile AOT-compiles, with `:compile-path "target/uberjar/classes"`. The standalone jar keeps its path `target/mcp-0.1.0-SNAPSHOT-standalone.jar`. AGENTS.md (CLAUDE.md) has an "AOT and the REPL" section, with the one-time cleanup of `target/classes` for older checkouts.

Known limit: plain `lein jar` now has `Main-Class mcp.server` without the AOT class, so only the uberjar runs with `java -jar` (as before in practice; README uses only `lein uberjar`).

Review fixed: the one-time cleanup note, and the project.clj comment points to AGENTS.md instead of repeating it.
