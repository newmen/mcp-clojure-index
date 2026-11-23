# Multilingual models by default: bge-m3 and bge-reranker-v2-m3

`vishalraj/nomic-embed-code` and `qllama/bce-reranker-base_v1` were chosen for English code. On docstrings that are not in English the BCE reranker cannot separate hits from noise: on a project with Russian docstrings every hit scored 0.47–0.49, and the vector search with the code model ranks Russian text poorly.

Decisions:

- The default embedding model is `bge-m3` (Ollama, 1024 dimensions, 100+ languages, English and Russian queries alike, no query or passage prefixes), and the default reranker is `qllama/bge-reranker-v2-m3`, run like the BCE model through `llama-server --rerank` (ADR-0007). Both models share the XLM-R tokenizer, so the 1000 characters the reranker reads per pair stay well under llama-server's 512-token batch (about 300 tokens of Russian docstrings).
- The collection records the embedding model of its points (payload `model`) and takes the vector size the model returns. When the configured model differs from the stored one, the collection is dropped and rebuilt, like a collection of an older payload format. The embedding cache keeps one directory per model, so vectors of another model are never reused.
- The code-specific pair stays a supported choice in `config.edn` (see the READMEs): for English-only docstrings, or when the smaller models matter (49 MB and 303 MB against 1.2 GB and 635 MB).

## Measurement

On 2026-11-23, local Qdrant 1.18.2, Ollama 0.32.0, the reranker in `llama-server` shipped with Ollama. Hits in the top 3 of `semantic_search`:

- Case 12: 12 queries (6 English, 6 Russian) against the 35 Definitions with Russian docstrings of `test-resources/fixtures/multilingual_project`.
- Case 9: the 8 queries of acceptance case 9 of `agent-usefulness` against `semantic_project`.

| Embedding model | Reranker | Case 12 | Case 9 |
|---|---|---|---|
| `vishalraj/nomic-embed-code` | none (fusion only) | 6/12 | 7/8 |
| `bge-m3` | none (fusion only) | 9/12 | 8/8 |
| `jeffh/intfloat-multilingual-e5-large:f16` (no prefixes) | none (fusion only) | 9/12 | 8/8 |
| `vishalraj/nomic-embed-code` | `qllama/bce-reranker-base_v1` (current) | **11/12** — fails | 8/8 |
| `bge-m3` | `qllama/bce-reranker-base_v1` | 11/12 | 8/8 |
| `vishalraj/nomic-embed-code` | `qllama/bge-reranker-v2-m3` | 12/12 | 8/8 |
| `bge-m3` | `qllama/bge-reranker-v2-m3` (chosen) | 12/12 | 8/8 |
| `jeffh/intfloat-multilingual-e5-large:f16` | `qllama/bge-reranker-v2-m3` | 12/12 | 8/8 |

With the current models `запретить выдачу должникам` puts `library.readers/block-reader` 5th, behind `unblock-reader`, `overdue-loans` and `return-book`; BCE scores of the expected hits are 0.42–0.59 and the best hit of each query lies in 0.43–0.64. With `bge-reranker-v2-m3` every expected Definition is in the top 3 (11 of 12 first), and the scores spread from 0.00 to 0.99.

The fixtures have fewer Search units than `:search/top-k`, so with a reranker running it reorders every unit and the embedding model does not change the result. The rows without a reranker measure the embedding model alone, which decides recall on a project larger than `:search/top-k`: there `bge-m3` and e5 find 9 of 12, the code model 6. Of the two `bge-m3` is chosen: same hits, no `query: `/`passage: ` prefixes to add, and an 8192-token input.

Relevance of the expected hits with the chosen pair (for `:search/min-relevance`, ticket 10): case 12 from 0.08 (`overdue-fine`, 2nd behind 0.19) to 0.98, best hit of each query at least 0.12; case 9 from 0.00 (`:shop.orders/email`, a spec without a docstring) and 0.02 (`shipping-cost`, 2nd behind 0.05) to 0.99.

## Threshold of `no confident matches`

`:search/min-relevance` is `0.05`: `semantic_search` puts `no confident matches (best X), try rephrasing in the docstrings' words` (followed by ` (they are mostly in <script>)` when the Descriptions are mostly in a non-Latin script) before the hits when the best relevance of a query is below it. Measured with the chosen pair on the same day, best hit of each query:

- 7 queries with no matching Search unit (JSON web tokens, 3D rendering, temperature conversion, push notifications, weather, satellite orbits; English and Russian), plus 5 more of the same kind in the measurement: 0.000–0.004 on `multilingual_project`, 0.000–0.024 on `semantic_project` (`convert temperature from Celsius to Fahrenheit` → `:smtp/server`).
- The 12 queries of case 12: 0.129 (`запретить выдачу должникам`) to 0.983.

`0.05` lies between them with room on both sides: twice the highest unrelated score, less than half of the weakest case 12 query. It is the relevance of the best hit that matters, so case 12's expected hit at 0.08 in second place does not trigger the line. Case 9 has queries whose best hit is 0.00–0.05 (a spec without a docstring, `shipping-cost`), so its queries may get the line while still finding the right hit; no threshold avoids that, and acceptance case 13 only requires case 12 to be free of it. The threshold belongs to this reranker: BCE put the best hit of every case 12 query at 0.43–0.64, so another reranker needs its own value.

The default was later raised to `0.3` (spec `agent-feedback-round-3`): a query whose best hit scores 0.15 is a weak answer, and an agent warned of it rephrases instead of opening random hits. The weakest case 12 queries (best 0.12 and 0.19) now get the line too, still followed by their hits; acceptance case 13 checks case 12 against the `0.05` measured here, which still separates it from the unrelated queries.

## Cross-language queries on a real project

On 2026-11-23 an agent reported that English queries miss Russian docstrings and asked for multilingual models or query translation. Measured with the chosen pair on that project: a large project, 3114 Search units, Russian docstrings, `:search/top-k` 100. Two questions, each asked several ways:

| Query | Result |
|---|---|
| 1, the agent's Russian query, in the docstrings' words | expected Definition 1st, 0.98 |
| 1, an English translation of it | 1st, 0.83 |
| 1, the agent's English query, naming the cache's key by an identifier the code does not use for it | not among the 100 vector candidates, 0.01 |
| 1, the same with the identifier the docstring uses | 2nd, 0.56 |
| 2, an English translation naming the steps the docstrings name | all three expected Definitions in the top 3, 0.96–0.99 |
| 2, the agent's English query, asking for a purpose no docstring mentions | the expected namespace 1st at 0.17, the others missed |

The agent's English queries were not translations: one named a different identifier, the other a purpose the code never states. With this pair the language of a query costs about 0.15 of relevance; its wording decides whether the hit is found at all. So the models stay, and queries are not translated. Instead `semantic_search` tells the agent to word queries in the docstrings' words and, when they are mostly in a non-Latin script, names that script (spec `agent-feedback-round-4`).

## Consequences

- Switching models re-embeds the whole project once (the collection is rebuilt and the cache directory of the new model starts empty).
- The case 12 test needs the reranker: without it even the multilingual embedding model finds 9 of 12.
- A running `llama-server` must be restarted with the new model file: the server ignores the model name in the request and answers with whatever model it loaded.
