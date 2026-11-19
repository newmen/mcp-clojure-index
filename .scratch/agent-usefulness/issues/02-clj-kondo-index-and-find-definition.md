# 02: clj-kondo index and `find_definition`

**What to build:** The server builds its precise index of Definitions from clj-kondo analysis (embedded as a library, ADR-0002), in memory, at startup, without touching Qdrant (ADR-0003). An agent calls `find_definition` with a simple name, an alias-qualified name (as written in some namespace) or a fully qualified name and gets `path:line  kind  ns/name  [arglists]  first docstring line`. An ambiguous simple name returns a short list of candidates instead of an error; a miss says what was searched (`not found (checked 214 files, including tests)`). The server upgrades its MCP protocol version and returns `instructions` from `initialize`: about five lines in English telling the agent to use this server instead of `grep` for Clojure code, what it sees that `grep` does not (aliases, `:refer`, `#'var`, `with-redefs`, values, keywords in EDN), that answers are compact `path:line` lines with a hard size limit, and to use `get_source` for bodies. The tool's description states the same advantage. Old `find_symbol` and `find_namespace` are removed.

**Blocked by:** 01 (Output budget and compact semantic search)

**Status:** resolved

- [x] Precise index is built from clj-kondo analysis at startup and is available even when Qdrant and Ollama are down.
- [x] Simple, alias-qualified and fully qualified names resolve to the right Definition; an ambiguous simple name returns candidates.
- [x] Answers follow the Output budget and the Compact answer rules; a miss reports the number of files checked.
- [x] `initialize` returns `instructions` in English; the protocol version supports that field.
- [x] `find_symbol` and `find_namespace` no longer appear in `tools/list`.
- [x] Acceptance test on this project's own code base (case 4 of the spec) runs in the default test run without external services.
