# MCP Clojure Code Index

MCP server for semantic indexing and analysis of Clojure code. Provides LLM agents (Kilo Code) with intelligent access to source code through search, navigation, and dependency analysis.

## Features

- **Semantic search** — vector search + reranking (Cross Encoder)
- **Symbol search** — functions, macros, vars by name (qualified or simple)
- **Namespace search** — namespace definition and metadata
- **Protocol and record search** — all methods of protocol/record
- **Dependency graph** — who calls (callers) and who is called (callees) by a function
- **Incremental indexing** — automatic tracking of file changes
- **State recovery** — in-memory structures are rebuilt from Qdrant on restart

## Requirements

- [Java](https://adoptium.net/) 21+
- [Leiningen](https://leiningen.org/) 2.11+ (development and build only)
- [Qdrant](https://qdrant.tech/) (vector database)
- [Ollama](https://ollama.com/) with models:
  - `vishalraj/nomic-embed-code` — code embeddings
  - `qllama/bce-reranker-base_v1` — result reranking

## Installation and running

### 1. Install and start the dependencies

<details>
<summary><b>Qdrant</b> — vector database</summary>

```bash
docker run -d -p 6333:6333 --name qdrant qdrant/qdrant
```

Check: `curl http://localhost:6333/collections`
</details>

<details>
<summary><b>Ollama</b> — embedding and reranking models</summary>

```bash
ollama pull vishalraj/nomic-embed-code
ollama pull qllama/bce-reranker-base_v1
```
</details>

### 2. Configure

The `resources/config.edn` file is already configured for the current project (`/Users/altermn/projects/clojure/mcp`). To index another project, change the `:index/root-path` field.

Main config fields:

| Key | Description |
|------|----------|
| `:qdrant/host` | Qdrant host |
| `:qdrant/port` | Qdrant port |
| `:qdrant/collection` | Collection name |
| `:ollama/url` | Ollama API address |
| `:ollama/embedding-model` | Embedding model |
| `:ollama/reranker-model` | Reranking model |
| `:index/root-path` | Path to the indexed project |
| `:index/include-extensions` | File extensions to index |
| `:index/exclude` | Directories to exclude |

### 3. Build uberjar

Before first run or after code changes, build the executable jar:

```bash
lein uberjar
```

### 4. Run the server

```bash
java -jar target/mcp-0.1.0-SNAPSHOT-standalone.jar resources/config.edn
```

The server starts in STDIO mode and waits for JSON-RPC 2.0 messages. On the first run it performs a full project indexing. On subsequent runs it restores the state from Qdrant and indexes only the changed files.

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

After configuration, restart Kilo Code. The MCP server `clojure-code-index` will appear in the interface. When enabled, Kilo automatically discovers these 8 tools:

| Tool | Description |
|-----|----------|
| `semantic_search` | Search code by natural language query |
| `find_symbol` | Find a symbol by name |
| `find_namespace` | Find a namespace |
| `find_callers` | Who calls a function |
| `find_callees` | Which function is called |
| `find_protocol` | Protocol methods |
| `find_record` | Record methods |
| `find_macro` | List of macros |

## Available MCP tools

All tools follow the JSON-RPC 2.0 protocol and are automatically registered in Kilo Code.

### semantic_search

```
Parameters: query (string)
Search: query embedding → Qdrant top-100 → Cross Encoder rerank → top-10
```

### find_symbol

```
Parameters: name (string) — qualified or simple symbol name
Returns: type, namespace, file, line, arglists, docstring
```

### find_callers / find_callees

```
Parameters: symbol (string) — qualified name (e.g. my.ns/my-func)
Returns: list of all callers/callees with file and line
```

## Architecture

```
src/mcp/
├── server.clj       — entry point, JSON-RPC over STDIO
├── tools.clj        — 8 MCP tools usings
├── parser.clj       — parsing .clj/.edn via rewrite-clj
├── symbol_index.clj — in-memory symbol index
├── graph.clj        — call graph (callers/callees)
├── embeddings.clj   — embeddings via Ollama
├── qdrant.clj       — vector storage (Qdrant HTTP API)
├── watcher.clj     — incremental indexing (WatchService)
├── search.clj      — hybrid search
└── config.clj      — configuration loading
```

## Development

```bash
# Tests
lein test

# Linter
clj-kondo --lint src/

# Build the uberjar (on code changes)
lein uberjar
```