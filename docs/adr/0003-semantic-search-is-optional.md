# Precise tools do not depend on Qdrant or Ollama

The precise index (Definitions, Usages) lives only in memory and is rebuilt from clj-kondo analysis at startup; nothing about it is stored in or restored from Qdrant. Qdrant holds only embeddings of Search units, and Ollama is used only for semantic search. If either service is down, every tool except `semantic_search` still works, and `semantic_search` answers that it is unavailable instead of failing. This removes the previous situation where "who calls X" broke whenever the vector database was unavailable or out of sync.

## Consequences

- The old Qdrant collections are dropped and recreated with the new payload; there is no migration.
- Embeddings are computed in the background, so `semantic_search` may report that embeddings are stale for N files while the precise tools are already fresh.
