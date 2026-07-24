# Architecture of the MCP server for semantic indexing of Clojure code

## Document list

| File | Contents |
|------|-----------|
| `1.OVERVIEW.md` | Overall architecture, 7 modules + config, DAG dependency graph |
| `2.DATA-MODELS.md` | Data models: CodeChunk, SymbolRecord, Edge, NamespaceRecord, ParseResult, ParseError, EmbeddingInput, SearchResult, IndexStats, Config |
| `3.INTERFACES.md` | Complete function signatures for each module |
| `4.NAMESPACE-MAP.md` | Directory structure of src/ and test/, topological layers |
| `5.FLOW.md` | Flow diagrams: indexing, search, incremental indexing, Kilo configuration |
| `6.EXTENSIBILITY.md` | Clojure protocols for replacing EmbeddingProvider, VectorStore, SymbolIndexProvider, GraphProvider |
| `7.COMPONENT-DIAGRAM.puml` | C4 Component diagram (PlantUML) |
| `8.SYSTEM-CONTEXT.puml` | C4 System Context diagram (PlantUML) |
| `9.TEST-PLAN.md` | Test plan: unit, integration, performance, acceptance criteria |

## Dependencies (DAG, topological order)

```
Layer 0: mcp.config
Layer 1: mcp.parser, mcp.embeddings
Layer 2: mcp.symbol-index, mcp.qdrant
Layer 3: mcp.graph
Layer 4: mcp.watcher, mcp.tools
Layer 5: mcp.server
```

There are no cyclic dependencies. Each module has a single responsibility.