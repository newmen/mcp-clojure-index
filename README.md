# MCP Clojure Code Index

MCP server that answers an LLM agent's questions about a Clojure code base precisely and compactly: where a name is defined, who uses it, what a namespace contains, the source of one Definition, the Implementations of a protocol or multimethod, and (optionally) where the code that does something is.

## Purpose

LLM agents spend most of their tokens on understanding code: `grep`, then reading files to see what each hit really is. This server answers questions like "where is X defined", "who uses X", "what is in this namespace" and "where is the code that does Y" with **compact** answers (one line per finding, hard size ceiling) that are **more complete than `grep`**: usages through any alias, `:refer`, `#'var`, `with-redefs`, functions passed as values, keywords in code and EDN. If an answer is ever bigger than the `grep` and reads that give the same information, or less precise, that is a bug. See `docs/adr/` for the principles and `GLOSSARY.md` for the vocabulary.

## How much it saves

**About 90% fewer tokens on questions about the code** than Claude's built-in tools (Grep and reading files). **Over a whole session it saves about 12–15%** of all tokens.

| What the agent asks | Tool | Built-in tools | With the server | Saved |
|---|---|---|---|---|
| Where is X defined, with its arglists, Type signature¹ and Docstring summary | `find_definition` | ~2,100 tokens | ~75 tokens | **96%** |
| The source of X | `get_source` | ~1,600 tokens | ~110 tokens | **93%** |
| Who uses X | `find_usages` | ~9,600 tokens | ~130 tokens | **99%** |
| Where is the code that does Y | `semantic_search` | ~17,000 tokens | ~390 tokens | **98%** |
| What is in a namespace | `namespace_summary` | ~840 tokens | ~250 tokens | **71%** |
| **A whole working session** | | | | **~12–15%** |

¹ With `:doc/signature` in the config (see [Configure](#2-configure)); both measured projects set it.

A session saves less because navigation is only part of it: in 123 real Claude Code sessions on two projects, grep and reading code that was not edited afterwards took 16% of the input tokens (13% in a median session); the system prompt, reasoning, edits and test runs cost the same with or without the server. The answers are also more precise: grep brought 7–10 lines per "who uses X" question that are not usages.

How it was measured: 20 sampled functions of each of two projects (this repository and a 220-file application), every namespace, and 12 hand-written "where is the code that does Y" questions; the baseline is what Claude Code does without the server — grep, then read 42% of the files whole and the rest in windows, the share counted in its transcripts. Even against an ideal agent that greps a precise pattern and reads only the lines it needs, the server saves 50–98% on every question except `namespace_summary`, which is then the same size but also gives arglists, Type signatures, specs and who requires the namespace. **The numbers vary from project to project**: they grow with file size and with how often names repeat. The method, the per-project numbers and the commands to measure your own project are in [ADR-0009](docs/adr/0009-measuring-savings-against-grep.md).

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
  - [Ollama](https://ollama.com/) with the `bge-m3` model — multilingual embeddings
  - optionally, a reranker: llama.cpp's `llama-server --rerank` with `qllama/bge-reranker-v2-m3` — sorts the hits precisely and gives them a relevance score

The default models are multilingual: queries in English or Russian (and 100+ other languages) find docstrings written in any of them. See [Choosing the models](#choosing-the-models) for the code-specific alternative.

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
ollama pull bge-m3
```
</details>

<details>
<summary><b>Reranker</b> (optional) — llama-server with bge-reranker-v2-m3</summary>

Ollama cannot run the reranking model itself (it has no rerank API), but it can download it, and Linux builds of Ollama ship `llama-server`, which can:

Bash:

```bash
ollama pull qllama/bge-reranker-v2-m3
MODEL=$(ollama show --modelfile qllama/bge-reranker-v2-m3 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Fish:

```
ollama pull qllama/bge-reranker-v2-m3
set MODEL (ollama show --modelfile qllama/bge-reranker-v2-m3 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Any `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp) works the same way. Check: `curl http://localhost:8012/health`. The server answers with the model it was started with, whatever `:reranker/model` says: restart it after changing the model.
</details>

<details id="choosing-the-models">
<summary><b>Choosing the models</b> — multilingual (default) or code-specific</summary>

| | Multilingual (default) | Code-specific |
|---|---|---|
| `:ollama/embedding-model` | `"bge-m3"` (1.2 GB, 1024 dimensions) | `"vishalraj/nomic-embed-code"` (49 MB, 768 dimensions) |
| `:reranker/model` and the llama-server model file | `"qllama/bge-reranker-v2-m3"` (635 MB) | `"qllama/bce-reranker-base_v1"` (303 MB) |
| Docstrings and queries | any language, English and Russian measured | English |

Choose the multilingual pair when docstrings or queries are not in English: with the code-specific pair every hit on Russian docstrings scores about the same and the expected Definition can fall out of the top 3. Choose the code-specific pair for a code base documented in English when memory or embedding time matters; on English docstrings both pairs find the same Definitions. The measurement is in `docs/adr/0008-multilingual-models.md`.

Changing `:ollama/embedding-model` re-embeds the project once: the Qdrant collection records its model and is rebuilt when it differs, and the embedding cache keeps one directory per model.
</details>

### 2. Configure

`resources/config.edn` is the default config. The project root is taken from `:index/root-path`, else the `QD_PROJECT_ROOT` or `VSCODE_CWD` environment variable, else the working directory.

| Key | Default | Description |
|------|---------|----------|
| `:index/root-path` | see above | Path to the indexed project |
| `:index/include-extensions` | `[".clj" ".cljc" ".cljs" ".edn"]` | File extensions to analyse |
| `:index/exclude` | `[]` | Directories to exclude; `.calva`, `.claude`, `.clj-kondo`, `.cpcache`, `.git`, `.kilo`, `.lsp`, `.mcp`, `logs`, `target`, `tmp` are always excluded |
| `:search/exclude` | `[]` | Search exclusions: paths (same syntax as `:index/exclude`) kept out of `semantic_search` and its embeddings, typically generated code; the precise tools still see them |
| `:output/limit` | `50` | Output budget: the maximum number of findings in one answer; a call's `limit` can only lower it |
| `:output/max-chars` | `4000` | Output budget: the maximum number of characters in one answer |
| `:doc/signature` | none (this repository's Project config sets `{:strategy :last-paragraph}`) | How the Type signature is found in a docstring: `:last-paragraph` (the last paragraph of a docstring with at least two), or `{:strategy :pattern :regex "..."}` (the first capture group of the regex; a regex without one is rejected at startup); without the key, or with `nil`, there are no signatures |
| `:qdrant/host`, `:qdrant/port` | `"localhost"`, `6333` | Qdrant (semantic search only) |
| `:qdrant/collection` | `QD_COLLECTION` env, else `<project dir>-collection` | Qdrant collection (semantic search only) |
| `:qdrant/recreate?` | `false` | Drop and recreate the collection at startup (semantic search only) |
| `:ollama/url` | `"http://localhost:11434/v1"` | Ollama API address (semantic search only) |
| `:ollama/embedding-model` | `"bge-m3"` | Embedding model (semantic search only); a change rebuilds the collection |
| `:reranker/url` | `"http://localhost:8012/v1"` | Reranker address, a `/v1/rerank` API such as `llama-server --rerank` (semantic search only, optional) |
| `:reranker/model` | `"qllama/bge-reranker-v2-m3"` | Model name sent to the reranker (semantic search only) |
| `:search/top-k` | `100` | Candidates taken from vector search and from word matching each, and fused candidates passed to the reranker (semantic search only) |
| `:search/re-rank-top` | `10` | Hits kept after reranking (semantic search only) |
| `:search/min-relevance` | `0.3` | When the best relevance is below it, `semantic_search` puts `no confident matches (best 0.15), try rephrasing in the docstrings' words` before the hits (plus ` (they are mostly in Cyrillic)` when most Descriptions are in one non-Latin script); set for `qllama/bge-reranker-v2-m3` (ADR-0008), raise it for a reranker whose scores of unrelated text are higher (semantic search only, needs the reranker) |
| `:embedding/cache-dir` | `<root>/.mcp/embedding-cache` | Embedding cache directory (semantic search only) |

An answer that hits the Output budget ends with a Truncation note, e.g. `truncated: shown 50 of 120, pass offset=50 or narrow the query`.

#### Project config

The config passed at start (the server config) holds the defaults for every project. A project may keep its own settings in a Project config: `.clojure-index.edn` at the project root (`:index/root-path`), committed with the project. It is optional; without it the server config applies as is. See ADR-0010.

- **Keys.** Any key of the table above is allowed. A key the server config cannot hold (a typo such as `:search/exlude`) is an error, not ignored.
- **Replacement.** A value replaces the server config's value whole; lists are not concatenated, so a project that needs the server's `:index/exclude` entries too repeats them. The always-excluded directories are added to `:index/exclude` either way. Patterns of `:index/exclude` and `:search/exclude` are regexes searched in the path relative to the project root, in which `.` matches only a dot.
- **Hot keys** apply from the next answer: the server compares the file's mtime (and presence) before every tool call, as it does for the sources. They are `:index/include-extensions`, `:index/exclude`, `:search/exclude`, `:search/min-relevance`, `:output/limit`, `:output/max-chars` and `:doc/signature`. A changed `:index/exclude` or `:index/include-extensions` drops newly excluded files from the index and analyses newly included ones; a changed `:search/exclude` removes the embeddings of newly excluded files and embeds un-excluded ones in the background. Deleting the file brings back the server config's values.
- **Cold keys** are every other key (Qdrant, Ollama, reranker, transport, `:search/top-k`, `:search/re-rank-top`, `:qdrant/collection`, `:embedding/cache-dir`, …). They are read at server start only. While the file's value of one differs from the running one, answers end with `.clojure-index.edn: :search/top-k changes after a server restart` (one line naming every such key).
- **Errors.** An unreadable or invalid file does not stop the server: it keeps the previous settings, and while the file stays broken, answers start with `project config error in .clojure-index.edn: <reason>; using previous settings`. A file invalid from the start leaves the server on the server config alone, with the same line. An invalid server config still stops the server at start, naming its absolute path.

The agent may edit the Project config itself; the server's instructions tell it so. A full example:

```clojure
{:index/exclude        ["dev" "test-resources"]          ; replaces the server's list
 :search/exclude       ["com/google/protobuf" "^src/gen/"] ; generated code, kept out of semantic_search only
 :search/min-relevance 0.4
 :output/limit         30
 :doc/signature        {:strategy :last-paragraph}
 :search/top-k         50}                                ; cold: applies after a restart
```

### 3. Build uberjar

Before first run or after code changes, build the executable jar:

```bash
lein uberjar
```

### 4. Run the server

```bash
java -jar target/mcp-0.1.0-SNAPSHOT-standalone.jar resources/config.edn
```

The server starts in STDIO mode and waits for JSON-RPC 2.0 messages. It analyses the project with clj-kondo at startup (in memory only) and computes embeddings of Search units in the background; on later runs only files changed since their embeddings were stored are re-embedded. A Qdrant collection of the old format is dropped and recreated. If the collection is deleted or emptied while the server runs, or replaced by one of another payload format, embedding model or vector size (by hand or by another server instance), the server notices on the next embedding update or semantic search and embeds every file again. Two instances with different embedding models sharing one `:qdrant/collection` keep replacing each other's collection; give them different collection names.

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
Input:  names (array of strings, required) — each simple, alias-qualified or fully qualified;
        `multi dispatch-value` for a defmethod (`area :circle`); a keyword for a spec
        doc ("summary" | "full", default "summary")
        limit, offset — in Definitions, counted across all names
Answer: one group per distinct name, like `rg --heading`, a blank line between groups:
        the name as asked, then one line per Definition
        path:line  kind  ns/name  [arglists]  Type signature  Docstring summary
        with doc "full": path:line  kind  ns/name  [arglists], then the whole docstring
        indented by two spaces (`  (no docstring)` when there is none)
```

A name that is not found says so in its header (`foo: not found (checked 214 files, including tests)`); an ambiguous one is headed by `ambiguous: N definitions named foo, pass a qualified name` and lists its candidates. The other names still answer. A page that starts inside a group repeats that group's header. An empty list answers `no names given`.

With `doc: "full"`, a single docstring longer than the Output budget is cut and ends with `truncated: docstring shown A of B chars`.

Example (`names: ["relative-path", "output/render"]`):

```
relative-path
src/mcp/output.clj:60  defn  mcp.output/relative-path  [root-path file-path]  Maybe String -> String -> String  Returns ...

output/render
src/mcp/output.clj:110  defn  mcp.output/render  ...
```

### find_usages

```
Input:  targets (array of strings, required) — names and/or keywords (`:k`, `:ns/k`, `::k`, `::alias/k`)
        kinds (array of strings) — only these Usage kinds:
          call, value, var-ref, redef, in-comment, require, keyword, edn (default: all)
        include_tests (boolean, default true)
        context (boolean, default true) — end each line with its Usage context
        warnings_only (boolean, default false) — only Usages that carry a Usage warning
        limit, offset
Answer: per target a header `target: N usages`, then one line per Usage,
        a blank line between targets
        path:line:col  usage-kind  ns/enclosing-definition  context  warning
```

The Usage context is up to 80 characters of the source line around the Usage, with whitespace runs collapsed; `…` marks a cut end.

A Usage warning ends the line when one holds: `wrong arity: 3 args, takes 1|2` (`takes 2+` for varargs) on a call whose argument count matches no arity of its target; on a `require` of a namespace target, `unused` when the file uses neither the alias nor any referred name (a call or value through the alias, a referred name, `#'`, `::alias/kw`, a fully qualified name), and `unused refer: b, c` when only those referred names are unused. Bare requires and `:refer :all` are never marked. `unused` is a hint: a use hidden in a macro or `resolve` is not seen.

An empty result states what was searched, e.g. `no usages of x (checked 214 files, including tests)`.

### namespace_summary

```
Input:  ns (string, required) — fully qualified namespace name
        detail (boolean, default false) — add Docstring summaries
        limit, offset
Answer: header   ns name  path  Docstring summary
        per Definition   line  kind  name  [arglists]  Type signature
                         (no Type signature when the Definition or the project has none)
                         (private as the source shows it: `defn-  name`, `def  ^:private name`;
                          the namespace's own specs as `::name`)
                         (with detail: ... Type signature  Docstring summary)
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
        starts with `no confident matches (best 0.03), try rephrasing in the docstrings' words`
        when the best relevance is below :search/min-relevance (never without the reranker),
        plus ` (they are mostly in <Script>)` when most Descriptions are in one non-Latin script
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
├── docstring.clj      — Docstring summary and Type signature strategies
├── text.clj           — whitespace collapsing and ellipsis cuts
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

# Tests that need Qdrant and Ollama with the default models; the Russian-docstring
# test (acceptance case 12) and the `no confident matches` test (case 13) also need
# the reranker, the others pass without it
lein test :integration

# Linter
clj-kondo --lint src test

# Build the uberjar (on code changes)
lein uberjar
```