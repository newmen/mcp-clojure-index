# 04: Project config: reading, hot answer keys, errors, restart notice

**What to build:** A project may keep a Project config, `.clojure-index.edn` at its root, that overrides any key of the server config; its values replace the server's whole, and an unknown key is an error. The server reads it at start and, before every `tools/call` next to the freshness check (ADR-0005), re-reads it when its mtime or presence changed. In this ticket the hot keys that only shape answers apply from the next answer: `:output/limit`, `:output/max-chars`, `:search/min-relevance`, `:doc/signature` (index keys follow in 05, `:search/exclude` in 06). A changed cold key is not applied, and answers end with `.clojure-index.edn: <keys> changes after a server restart` while the file differs from the running value. An invalid or unreadable file keeps the previous settings, and answers start with `project config error in .clojure-index.edn: <reason>; using previous settings`; invalid from start means the server config alone. Deleting the file brings back the server config. An invalid server config still stops the server, naming its absolute path. This repository's own settings (`:index/exclude ["dev" "test-resources"]`, `:doc/signature {:strategy :last-paragraph}`) move from the shipped server config into this repository's `.clojure-index.edn`. See spec `.scratch/agent-feedback-round-3/spec.md`, section Project config, and ADR-0010.

**Blocked by:** 03 (both change the shipped server config)

**Status:** resolved

- [x] `.clojure-index.edn` at the project root is read at start; any server config key is allowed and replaces the server value whole; always-excluded directories are still added.
- [x] An unknown key, a non-compiling pattern, a bad signature strategy or unreadable EDN gives the `project config error in .clojure-index.edn: …; using previous settings` line, with the previous settings still in use.
- [x] A Project config invalid from the start runs the server on the server config alone and reports the error the same way.
- [x] Editing a hot answer key applies from the next `tools/call`; deleting the file restores the server config.
- [x] Changing a cold key is not applied and answers end with the restart line naming every such key.
- [x] An invalid server config stops the server with its absolute path in the message.
- [x] The shipped server config holds defaults only; this repository's settings live in its `.clojure-index.edn`, and the shared test config picks them up so existing tests keep their exclusions and Type signatures.
- [x] Server-seam tests on a temporary project cover: edit applied, broken file, cold key, deleted file, invalid from start.
