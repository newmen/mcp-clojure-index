# MCP Clojure Code Index

MCP server that answers an LLM agent's questions about a Clojure code base precisely and compactly: where a name is defined, who uses it, what a namespace contains, the source of one Definition, the Implementations of a protocol or multimethod, and (optionally) where the code that does something is.

## Purpose

LLM agents spend most of their tokens on understanding code: `grep`, then reading files to see what each hit really is. This server answers questions like "where is X defined", "who uses X", "what is in this namespace" and "where is the code that does Y" with **compact** answers (one line per finding, hard size ceiling) that are **more complete than `grep`**: usages through any alias, `:refer`, `#'var`, `with-redefs`, functions passed as values, keywords in code and EDN. If an answer is ever bigger or less precise than what `grep` would give, that is a bug. See `docs/adr/` for the principles and `GLOSSARY.md` for the vocabulary.

## Features

- **Definitions from clj-kondo analysis** — vars, macros, tests (`deftest`), multimethods and their methods, specs (`s/def`), protocols, records, forms of project macros declared via `lint-as` in the project's `.clj-kondo/config.edn`
- **Usages more complete than `grep`** — aliases, `:refer`, `#'var`, functions passed as values, `with-redefs`, `comment` forms, `:require`, keywords in code and in `.edn` files
- **Output budget** — every answer is one line per finding with a hard ceiling in lines and characters, and pages with `offset`
- **Freshness** — files changed on disk are re-analysed before each answer; embeddings catch up in the background
- **Qdrant and Ollama are optional** — they are needed only for `semantic_search`; all other tools work without them

## Requirements

- [Java](https://adoptium.net/) 21+
- [Leiningen](https://leiningen.org/) 2.11+ (development and build only)
- Only for `semantic_search` (optional):
  - [Qdrant](https://qdrant.tech/) (vector database)
  - [Ollama](https://ollama.com/) with the `vishalraj/nomic-embed-code` model — code embeddings
  - optionally, a reranker: llama.cpp's `llama-server --rerank` with `qllama/bce-reranker-base_v1` — sorts the hits precisely and gives them a relevance score

If Qdrant or Ollama is not reachable, the server still starts and every precise tool answers; `semantic_search` answers that it is unavailable. Without the reranker `semantic_search` still works: hits are ordered by vector search fused with word matching on names and docstrings, and carry no score.

## Installation and running

### 1. Install and start the optional dependencies (semantic search only)

<details>
<summary><b>Qdrant</b> — vector database</summary>

```bash
docker run -d -p 6333:6333 --name qdrant qdrant/qdrant
```

Check: `curl http://localhost:6333/collections`
</details>

<details>
<summary><b>Ollama</b> — embedding model</summary>

```bash
ollama pull vishalraj/nomic-embed-code
```
</details>

<details>
<summary><b>Reranker</b> (optional) — llama-server with the BCE model</summary>

Ollama cannot run the reranking model itself (it has no rerank API), but it can download it, and Linux builds of Ollama ship `llama-server`, which can:

Bash:

```bash
ollama pull qllama/bce-reranker-base_v1
MODEL=$(ollama show --modelfile qllama/bce-reranker-base_v1 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Fish:

```
ollama pull qllama/bce-reranker-base_v1
set MODEL (ollama show --modelfile qllama/bce-reranker-base_v1 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Any `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp) works the same way. Check: `curl http://localhost:8012/health`
</details>

### 2. Configure

`resources/config.edn` is the default config. The project root is taken from `:index/root-path`, else the `QD_PROJECT_ROOT` or `VSCODE_CWD` environment variable, else the working directory.

| Key | Default | Description |
|------|---------|----------|
| `:index/root-path` | see above | Path to the indexed project |
| `:index/include-extensions` | `[".clj" ".cljc" ".cljs" ".edn"]` | File extensions to analyse |
| `:index/exclude` | `[]` | Directories to exclude; `.calva`, `.claude`, `.clj-kondo`, `.cpcache`, `.git`, `.kilo`, `.lsp`, `.mcp`, `logs`, `target`, `tmp` are always excluded |
| `:output/limit` | `50` | Output budget: the maximum number of findings in one answer; a call's `limit` can only lower it |
| `:output/max-chars` | `4000` | Output budget: the maximum number of characters in one answer |
| `:doc/signature` | `{:strategy :last-paragraph}` | How the Type signature is found in a docstring: `:last-paragraph` (the last paragraph of a docstring with at least two), or `{:strategy :pattern :regex "..."}` (the first group of the regex); `nil` turns signatures off |
| `:qdrant/host`, `:qdrant/port` | `"localhost"`, `6333` | Qdrant (semantic search only) |
| `:qdrant/collection` | `QD_COLLECTION` env, else `<project dir>-collection` | Qdrant collection (semantic search only) |
| `:qdrant/recreate?` | `false` | Drop and recreate the collection at startup (semantic search only) |
| `:ollama/url` | `"http://localhost:11434/v1"` | Ollama API address (semantic search only) |
| `:ollama/embedding-model` | `"vishalraj/nomic-embed-code"` | Embedding model (semantic search only) |
| `:reranker/url` | `"http://localhost:8012/v1"` | Reranker address, a `/v1/rerank` API such as `llama-server --rerank` (semantic search only, optional) |
| `:reranker/model` | `"qllama/bce-reranker-base_v1"` | Model name sent to the reranker (semantic search only) |
| `:search/top-k` | `100` | Candidates taken from vector search and from word matching each, and fused candidates passed to the reranker (semantic search only) |
| `:search/re-rank-top` | `10` | Hits kept after reranking (semantic search only) |
| `:embedding/cache-dir` | `<root>/.mcp/embedding-cache` | Embedding cache directory (semantic search only) |

An answer that hits the Output budget ends with a Truncation note, e.g. `truncated: shown 50 of 120, pass offset=50 or narrow the query`.

### 3. Build uberjar

Before first run or after code changes, build the executable jar:

```bash
lein uberjar
```

### 4. Run the server

```bash
java -jar target/mcp-0.1.0-SNAPSHOT-standalone.jar resources/config.edn
```

The server starts in STDIO mode and waits for JSON-RPC 2.0 messages. It analyses the project with clj-kondo at startup (in memory only) and computes embeddings of Search units in the background; on later runs only files changed since their embeddings were stored are re-embedded. A Qdrant collection of the old format is dropped and recreated.

## Integration with Kilo Code

### Global configuration (available from any project)

The server is run via the uberjar, not via `lein run`. This is necessary because Kilo uses a 30s timeout, while `lein run` requires more than 60s for JVM + Leiningen. Use absolute paths for the `command`, the jar, and the `config.edn`, to avoid problems with the `PATH` when starting from Kilo.

Add to `~/.config/kilo/kilo.jsonc`:

```json
{
  "mcp": {
    "clojure-code-index": {
      "type": "local",
      "command": ["/usr/bin/java", "-jar", "/path/to/mcp/target/mcp-0.1.0-SNAPSHOT-standalone.jar", "/path/to/mcp/resources/config.edn"],
      "workingDirectory": "/path/to/mcp",
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

Replace `/path/to/mcp` with the full path to the copy of this project, and `/usr/bin/java` with the path to your JDK.

### Local configuration (for a single project)

Create `kilo.json` at the project root or `.kilo/kilo.jsonc`:

```json
{
  "mcp": {
    "clojure-code-index": {
      "type": "local",
      "command": ["/usr/bin/java", "-jar", "/path/to/mcp/target/mcp-0.1.0-SNAPSHOT-standalone.jar", "/path/to/mcp/resources/config.edn"],
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

### Verification

After configuration, restart Kilo Code. The MCP server `clojure-code-index` will appear in the interface. When enabled, Kilo automatically discovers these 6 tools:

| Tool | Description |
|-----|----------|
| `find_definition` | Where a name is defined |
| `find_usages` | Every Usage of one or more names and/or keywords |
| `namespace_summary` | The Definitions of a namespace and who requires it |
| `get_source` | The source of exactly one Definition |
| `find_implementations` | The Implementations of a protocol or multimethod |
| `semantic_search` | Definitions and EDN entries by meaning (needs Qdrant and Ollama) |

## Available MCP tools

These are exactly the tools returned by `tools/list`. Names can be simple (`relative-path`), alias-qualified as written in some namespace (`output/relative-path`) or fully qualified (`mcp.output/relative-path`); an ambiguous name returns the candidates. Every tool except `get_source` accepts `limit` (can only lower `:output/limit`) and `offset` (findings to skip, for paging). Every answer obeys the Output budget. The Docstring summary in answer lines is the first sentence of the docstring, followed by `(+N chars)` when more text remains. The index is re-read from changed files (by mtime) before every answer, so edits are visible immediately.

### find_definition

```
Input:  name (string, required) — simple, alias-qualified or fully qualified;
        `multi dispatch-value` for a defmethod (`area :circle`); a keyword for a spec
        doc ("summary" | "full", default "summary")
        limit, offset — in Definitions
Answer: one line per Definition
        path:line  kind  ns/name  [arglists]  Type signature  Docstring summary
        with doc "full": path:line  kind  ns/name  [arglists], then the whole docstring
        indented by two spaces (`  (no docstring)` when there is none)
```

With `doc: "full"`, a single docstring longer than the Output budget is cut and ends with `truncated: docstring shown A of B chars`.

Example: `src/mcp/output.clj:25  defn  mcp.output/relative-path  [root-path file-path]  Return ...`

### find_usages

```
Input:  targets (array of strings, required) — names and/or keywords (`:k`, `:ns/k`, `::k`, `::alias/k`)
        kinds (array of strings) — only these Usage kinds:
          call, value, var-ref, redef, in-comment, require, keyword, edn (default: all)
        include_tests (boolean, default true)
        context (boolean, default true) — end each line with its Usage context
        warnings_only (boolean, default false) — only Usages that carry a Usage warning
        limit, offset
Answer: per target a header `target: N usages`, then one line per Usage
        path:line:col  usage-kind  ns/enclosing-definition  context  warning
```

The Usage context is up to 80 characters of the source line around the Usage, with whitespace runs collapsed; `…` marks a cut end.

A Usage warning ends the line when one holds: `wrong arity: 3 args, takes 1|2` (`takes 2+` for varargs) on a call whose argument count matches no arity of its target; on a `require` of a namespace target, `unused` when the file uses neither the alias nor any referred name (a call or value through the alias, a referred name, `#'`, `::alias/kw`, a fully qualified name), and `unused refer: b, c` when only those referred names are unused. Bare requires and `:refer :all` are never marked. `unused` is a hint: a use hidden in a macro or `resolve` is not seen.

An empty result states what was searched, e.g. `no usages of x (checked 214 files, including tests)`.

### namespace_summary

```
Input:  ns (string, required) — fully qualified namespace name
        limit, offset
Answer: header   ns name  path  Docstring summary
        per Definition   line  public|private  kind  name  [arglists]  Docstring summary
        last line        required by: ns1, ns2, ... (or `none`)
```

### get_source

```
Input:  name (string, required) — simple, alias-qualified or fully qualified
        limit, offset — in lines of the body (or in candidates of an ambiguous name)
Answer: a `path:start-end` header line, then the source of exactly one Definition;
        a body over the Output budget ends with a truncation note, pass offset for the rest;
        a single line longer than the budget is cut and ends with `truncated: source shown A of B chars`
```

### find_implementations

```
Input:  name (string, required) — protocol or multimethod
        limit, offset
Answer: one line per Implementation (defrecord, deftype, extend-type, extend-protocol, reify, defmethod)
        path:line  ns/type-or-dispatch-value  method
```

### semantic_search

```
Input:  query (string, required) — what the code does, in natural language
        include_tests (boolean, default false) — also search Definitions in Test paths
        limit, offset
Search: query embedding → Qdrant top :search/top-k ─┐
        words of names and docstrings (BM25) ───────┴→ reciprocal rank fusion → reranker → top :search/re-rank-top
Answer: one line per hit, no source bodies
        score  path:line  kind  ns/name  Docstring summary
        (score: the reranker's relevance, 0..1; left out when the reranker is unavailable)
        ends with `embeddings stale for N files` while recent edits are being embedded
```

Needs Qdrant and Ollama; without them it answers that semantic search is unavailable.

## Architecture

```
src/mcp/
├── server.clj         — entry point, JSON-RPC over STDIO, freshness check before each tool call
├── config.clj         — configuration loading
├── code_index.clj     — in-memory clj-kondo analysis of the project (Definitions, Usages)
├── usages.clj         — Usages and their kinds
├── output.clj         — Output budget and Compact answer helpers
├── tool.clj           — tool registry (spec/call multimethods)
├── tools.clj          — tools/list and tools/call
├── tools/             — one namespace per tool
├── search_units.clj   — Search units for semantic search
├── semantic_index.clj — background embedding of Search units
├── embeddings.clj     — embeddings via Ollama, reranking via a /v1/rerank server
├── lexical.clj        — word matching (BM25) on Search unit names and docstrings
├── qdrant.clj         — vector storage (Qdrant HTTP API)
└── search.clj         — vector search + word matching, rank fusion, reranking
```

## Development

```bash
# Tests (no Qdrant or Ollama needed)
lein test

# Tests that need Qdrant and Ollama (they pass with or without the reranker)
lein test :integration

# Linter
clj-kondo --lint src test

# Build the uberjar (on code changes)
lein uberjar
```