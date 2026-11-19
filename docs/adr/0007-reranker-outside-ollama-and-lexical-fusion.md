# The reranker runs outside Ollama, and word matching is fused in before it

Ollama (0.32) cannot run the BCE cross-encoder `qllama/bce-reranker-base_v1`: it has no rerank API, and its embedding runner crashes on the model (`GGML_ASSERT(n_outputs_max <= cparams.n_outputs_max)`). The first reranker called Ollama's embeddings endpoint, swallowed the error and silently kept the vector order, so `semantic_search` ran on vector search alone and missed half of acceptance case 9 (4 of 8 expected Definitions in the top 3).

Decisions:

- The reranker is a separate, optional server speaking the `/v1/rerank` API of llama.cpp's `llama-server --rerank` (`:reranker/url`). The model file still comes from `ollama pull`; Linux builds of Ollama ship `llama-server`. Its scores are logits, shown as their sigmoid (relevance in 0..1). It reads query and Search unit text together up to 1000 characters, because the model takes 512 tokens per pair and the server rejects the whole request when one pair is longer; a malformed answer drops the reranker for that query instead of failing it.
- Before the reranker, vector candidates are fused by reciprocal rank fusion with lexical candidates: BM25 over the words of the Search unit name and its whole docstring, over all Search units of the current precise index. This is the "second step" of acceptance case 9 in the spec.

Measured on the 8 queries of acceptance case 9: vector search alone 4/8 in the top 3; the BCE reranker over all units 8/8 at rank 1; fusion without the reranker 8/8 at rank 1. Both steps are kept: fusion gives recall when the vector search misses a unit whose name or docstring says exactly what was asked, and works with Ollama alone; the reranker gives precise order and a meaningful score when it is running.

## Consequences

- `semantic_search` needs Qdrant and Ollama as before (ADR-0003); the reranker is optional on top. Without it hits have no score and keep the fused order.
- The fixture's docstrings are close to the queries, so fusion alone does better there than it will on a real project; the reranker is the recommended setup.
- Search units are rebuilt from the precise index on each query for the lexical step (about 30 ms for 600 units); cache them if that becomes noticeable.
