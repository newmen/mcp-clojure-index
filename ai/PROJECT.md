# Project: MCP server for semantic indexing and analysis of Clojure code

## Overall goal

Develop a local MCP server that provides LLM agents (e.g. Kilo Code) with intelligent access to the source code of Clojure projects. The server must build a semantic index of the source code that accounts for the structure of the language (s-expressions), store embeddings in Qdrant, support incremental reindexing, perform hybrid search (vector + structural), and provide a set of MCP tools for searching, navigating, and analyzing code.

The architecture must be modular so that individual components (e.g. the embedding model, reranker, or vector store) can later be replaced without changing the rest of the system.

On server restart, the in-memory structures (SymbolIndex, Graph) are restored from Qdrant (scroll payload) rather than rebuilt from scratch. Qdrant is the single source of truth for the saved state.

## Project architecture

Files in the /Users/altermn/projects/clojure/mcp/ai/arch/ directory.

## Target project structure

src/
   mcp/
      server.clj
      tools.clj
      qdrant.clj
      embeddings.clj
      parser.clj
      graph.clj
      symbol_index.clj
      watcher.clj
resources/
   config.edn
test/
    mcp/
        ...
project.clj

## Target MCP server API

semantic_search(query)
find_symbol(name)
find_namespace(name)
find_callers(symbol)
find_callees(symbol)
find_protocol(name)
find_record(name)
find_macro(name)

## Indexing flow (MCP server)

```
File System
↓
File Change Detection (WatchService / Initial Scan)
↓
rewrite-clj Parser
↓
Concrete Syntax Tree (CST)
↓
Semantic Chunking (s-expressions)
↓
Metadata Extraction
↓
Symbol Index
↓
Dependency Graph
↓
Embedding Generation (Ollama)
↓
Qdrant Upsert
```

## Agent flow

```
Agent thinking
↓
MCP Tool Call
↓
Embedding Generation (Query)
↓
Qdrant Search
↓
Top100
↓
Cross Encoder Re-ranking
↓
Top10
↓
(Optional) Symbol Index / Dependency Graph Expansion
↓
Context Assembly
↓
LLM Response
```

## Restart recovery flow

```
Server Start
↓
Qdrant collection exists?
├── No → reindex-all!
└── Yes → scroll-all (payload only, without vectors)
          ↓
          SymbolIndex + Graph (build-index / build-graph)
          ↓
          Compare hashes with the file system
          ├── Hash matches → skip
          ├── Hash does not match → process-modify
          └── File deleted → process-delete
          ↓
          Watcher start
```

## Models

For the corresponding tasks, the following models should be used by default:

Embedding Generation: vishalraj/nomic-embed-code
Cross Encoder Re-ranking: qllama/bce-reranker-base_v1

Both models are installed locally in [ollama](http://localhost:11434/v1).

## Indexing verification

As an example Clojure project to verify how indexing works, use the same (/Users/altermn/projects/clojure/mcp/) project.