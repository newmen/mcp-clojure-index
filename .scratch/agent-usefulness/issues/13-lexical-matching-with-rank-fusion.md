# 13: Lexical matching on names and docstrings with rank fusion

**What to build:** Acceptance case 9 (`^:integration` test `mcp.tools.semantic-search-test/expected-definition-is-in-the-top-3-of-semantic-search`, run with `lein test :integration`) fails after ticket 11. Add lexical matching on Search unit names and docstrings (e.g. BM25 over `ns/name` split into words, plus the docstring), and combine it with the vector ranking by reciprocal rank fusion before the reranker. This is the second step the spec describes for acceptance case 9.

**Blocked by:** 11 (Semantic search on Search units)

**Status:** resolved

Observed on 2026-11-19 against the `semantic_project` fixture (local Qdrant, Ollama `vishalraj/nomic-embed-code` + `qllama/bce-reranker-base_v1`): 4 of 8 queries miss the top 3.

| query | expected | top 3 returned |
|---|---|---|
| sum the price of all items in an order | `shop.orders/order-total` | `shipping-cost :courier`, `:shop.orders/email`, `shop.orders-test` (expected is 5th) |
| reduce an amount by a percentage discount | `shop.orders/apply-discount` | `:db/connection-pool`, `reserve-stock`, `send-invoice-email` (expected is 7th) |
| put delivered units back into the warehouse | `shop.inventory/restock-product` | `:db/connection-pool`, `reserve-stock`, `send-invoice-email` (expected not in top 10) |
| spec validating an email address | `:shop.orders/email` | `:db/connection-pool`, `shipping-cost :courier`, `restock-product` (expected is 4th) |

The other four queries pass: "decrease stock when an order is placed", "email the invoice to the customer", "database connection pool settings" and "shipping price for a courier delivery".

All scores sit in a narrow band (0.88-0.93), and `:db/connection-pool` ranks first for unrelated queries. This suggests the reranker is not reordering anything: `mcp.embeddings/rerank` is a placeholder that calls the embeddings endpoint and falls back to the vector score. Check that first, because a working cross-encoder may make lexical fusion unnecessary or less important.

- [x] Check whether `embeddings/rerank` actually reranks with the BCE model. Fix it or record why not.
- [x] Lexical scoring over Search unit names (words of `ns/name`, EDN key) and docstrings.
- [x] Reciprocal rank fusion of lexical and vector candidates before the reranker.
- [x] Acceptance case 9 passes: every expected Definition is in the top 3 and every answer fits the Output budget.

## Comments

2026-11-19: `embeddings/rerank` never reranked. Ollama 0.32 has no rerank API and its embedding runner crashes on the BCE model; the error was swallowed and the vector order kept (the earlier scores were not reranker scores). The reranker now calls a `/v1/rerank` server (`llama-server --rerank`, shipped with Ollama on Linux) at `:reranker/url`; optional. Lexical BM25 over unit names and first docstring lines is fused with the vector candidates by RRF before it. Acceptance case 9 passes both without the reranker and with it. See ADR-0007.
