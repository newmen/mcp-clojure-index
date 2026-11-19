# Spec: make the server useful to agents

Status: ready-for-agent

## Problem

In real refactoring sessions agents abandon the server after the first call and do the rest with `grep`. Observed causes:

- `semantic_search` returned ~595k characters for one query (hits expanded with every definition of the same files and all graph neighbours, each with full source). The client could not even show it.
- One bad answer made the agent distrust the whole server, including tools that might have worked.
- `find_callers` cannot see calls from tests (`deftest`, `defmethod`, `s/def` and project macros are not recognised as definitions), misses `:refer`, `#'var` and other aliases, produces false positives by resolving unknown names to the first definition with the same simple name, and returns bare names without file and line.
- Keyword usages cannot be searched at all; `find_protocol` / `find_record` always return nothing.
- Tool descriptions don't say why the server is better than `grep`, and the tools are deferred, so the first use already costs more than `grep`.
- Precise tools break when Qdrant is unavailable, because the graph is restored from it.

`grep` wins because its answer is predictable in size and shape and can be acted on immediately. The server must win on the same terms and be more complete (ADR-0001).

## Decisions

See `docs/adr/0001`–`0007` and `GLOSSARY.md`. In short:

- Definitions and Usages come from clj-kondo analysis, embedded as a library, honouring the project's `.clj-kondo/config.edn` (ADR-0002).
- The precise index is in memory only; Qdrant and Ollama serve semantic search only and are optional (ADR-0003).
- Output budget is a server-side ceiling: `:output/limit` 50, `:output/max-chars` 2000 (ADR-0004).
- Synchronous mtime check before each answer, no WatchService (ADR-0005).
- Compact answers only, nothing the agent did not ask for (ADR-0006).
- The reranker is a separate optional `/v1/rerank` server; lexical matching is fused with vector search before it (ADR-0007).
- No backward compatibility with the old tool names.

## Tool surface

All tools obey the Output budget and accept `limit` (lower only) and `offset`. Names can be simple (`get-uuid`), alias-qualified as written in some namespace, or fully qualified; an ambiguous name returns a short list of candidates instead of an error.

| Tool | Input | Answer (one line per finding) |
|---|---|---|
| `find_definition` | `name` | `path:line  kind  ns/name  [arglists]  first docstring line` |
| `find_usages` | `targets` (one or more names and/or keywords), `kinds?`, `include_tests?` (default true) | `path:line:col  usage-kind  ns/enclosing-definition` grouped by target |
| `namespace_summary` | `ns` | public and private Definitions with arglists and first docstring line; then the namespaces that require it |
| `get_source` | `name` | the source of exactly one Definition, with `path:start-end` header |
| `find_implementations` | `name` (protocol or multimethod) | `path:line  ns/type-or-dispatch-value  method` |
| `semantic_search` | `query`, `limit?` | `score  path:line  kind  ns/name  first docstring line`; no bodies, no expansion; `score` is the reranker's relevance, left out when the reranker is not running (ADR-0007) |

Removed: `find_symbol`, `find_namespace`, `find_callers`, `find_callees`, `find_protocol`, `find_record`, `find_macro`.

Usage kinds: `call`, `value`, `var-ref`, `redef`, `in-comment`, `require`, `keyword`, `edn`. Open implementation question for the `find_usages` ticket: how to derive `value`, `redef` and `in-comment` from clj-kondo output (e.g. from call position/arity and the enclosing form's range); verify against clj-kondo before designing.

Empty results state what was searched: `no usages of X (checked 214 files, including tests)`.

Tool descriptions say explicitly what the tool sees that `grep` does not and how large the answer is.

## Server instructions

Upgrade the MCP protocol version so that `initialize` returns `instructions` (in English, ~5 lines): use this server instead of `grep` for questions about Clojure code; usages include aliases, `:refer`, `#'var`, `with-redefs`, values and keywords in EDN; answers are compact `path:line` lines under a hard size ceiling; use `get_source` for bodies.

## Scope of analysis

- All Clojure files (`.clj`, `.cljc`, `.cljs`) and `.edn` files of the project, minus configured excludes. `project.clj` and `deps.edn` are analysed as ordinary files.
- Test paths come from `project.clj` `:test-paths` or `deps.edn` alias `:extra-paths` (aliases containing `test`); fallback: `test/` and `*_test.clj*` files.
- Search units: every Definition (including `deftest`, `defmethod`, `s/def`, project macros) and every top-level entry of an EDN map. Embedding text: `ns/name` + arglists + docstring + source truncated to ~2000 characters. The reranker stays, as a separate optional server (ADR-0007).
- Other resources (`.sql`, `.html`, `.json`) are out of scope.

## Freshness

Before each precise-tool answer, changed files (by mtime) are re-analysed synchronously. Embeddings of changed files are recomputed in the background; until then `semantic_search` appends `embeddings stale for N files`.

## Acceptance

Automated tests on this project's own code base, plus fixtures under `test-resources/` where the code base lacks a case. Precise-tool tests run without Qdrant/Ollama in the default `lein test`. Every case asserts both completeness against a hand-checked expected list and answer size within the budget. Required cases:

1. Usages of a function called through an alias, through `:refer`, via `#'var`, passed as a value (`partial`, `map`), redefined in `with-redefs` in a test, and mentioned inside `comment`; each reported with its correct kind.
2. `find_usages` for several targets in one call.
3. Usages of a keyword in code and in an `.edn` file.
4. `find_definition` for a simple, an alias-qualified and a fully qualified name; an ambiguous simple name returns candidates.
5. Definitions created by `deftest`, `defmethod`, `s/def` and a project macro declared via `lint-as`.
6. An answer larger than the budget ends with a Truncation note, and `offset` returns the next page.
7. Edit a file, ask again immediately: the answer reflects the edit.
8. Qdrant/Ollama unavailable: precise tools answer; `semantic_search` says it is unavailable.
9. `^:integration`: for a fixed set of queries against fixtures, the expected Definition is in the top 3 of `semantic_search`, and the answer fits the budget. If not, add lexical matching on names and docstrings with rank fusion before the reranker (second step, only if this test demands it). Done in ticket 13 (ADR-0007): the test failed on vector search alone; fusion is kept alongside the repaired reranker.

## Delivery

Vertical slices, one tool at a time, test-first (TDD), old code deleted as each slice replaces it. Suggested order:

1. Skeleton: clj-kondo analysis in memory, Output budget and answer formatting, `instructions`, test-path detection.
2. `find_definition`.
3. `find_usages`.
4. `namespace_summary`.
5. `get_source`.
6. `find_implementations`.
7. Freshness check; remove the watcher.
8. `semantic_search` on new Search units; make Qdrant/Ollama optional; drop old collections.
9. Delete `parser`, `graph`, `symbol-index` and remaining dead code; update README tool docs.

## Out of scope

- Indexing non-Clojure resources.
- Several projects per server process (one process per project root, as today).
- Lexical/hybrid search beyond the fusion of ADR-0007.
