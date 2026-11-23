# 03: the semantic index does not notice a collection deleted by another instance

**Status:** resolved

`mcp.semantic-index/sync!` calls `prepare!` only while `:prepared?` is false, and `:embedded` keeps the mtimes of files already stored. If another server instance (another payload format or embedding model) or the user deletes or recreates the Qdrant collection, this instance keeps `:prepared? true`, sees no stale files, and never recreates or refills the collection: `semantic_search` answers from an empty or foreign collection until restart.

Detect it, e.g. when Qdrant answers 404 or the collection's vector size/format marker differs: reset `:prepared?` and `:embedded`, then prepare and re-embed.

## Comments

Implemented. `qdrant/collection-lost?` recognises a Qdrant failure (ex-data `:qdrant/status`, so Ollama failures never count) that says the collection is gone (404) or takes vectors of another size (400 "Vector dimension error"), and `qdrant/search` throws when a hit was stored with another format or model. `semantic-index/forget-lost-collection!` then resets `:prepared?` and `:embedded`; it runs from `sync!` and from `semantic_search`, which answers that the collection is being rebuilt. The next sync prepares the collection and re-embeds every file.

Known limits:
- A collection recreated empty with the same layout and vector size is not noticed: writes succeed and an empty search shows no foreign layout. Only files that become stale are refilled.
- A foreign collection with the same vector size is noticed only by a search, not by `sync!` writes.
- Two instances with different models on one collection keep replacing each other's (README says to use different collection names).
