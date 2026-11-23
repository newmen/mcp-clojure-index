# 03: the semantic index does not notice a collection deleted by another instance

**Status:** resolved

`mcp.semantic-index/sync!` calls `prepare!` only while `:prepared?` is false, and `:embedded` keeps the mtimes of files already stored. If another server instance (another payload format or embedding model) or the user deletes or recreates the Qdrant collection, this instance keeps `:prepared? true`, sees no stale files, and never recreates or refills the collection: `semantic_search` answers from an empty or foreign collection until restart.

Detect it, e.g. when Qdrant answers 404 or the collection's vector size/format marker differs: reset `:prepared?` and `:embedded`, then prepare and re-embed.

## Comments

Implemented. `qdrant/verify-layout!` checks the collection: it is lost when it is missing or empty although this server stored points in it, or when any point was stored with another payload format or embedding model (`stored-layout` asks Qdrant for a point outside the current layout first, so a collection mixing layouts is never taken for current). `semantic_search` runs the check before every search, and `sync!` before every update of a prepared collection. `qdrant/collection-lost?` also recognises a write or search that Qdrant answers with 404 or with 400 "Vector dimension error"; every ex-data key of the adapter is in the `qdrant` namespace, so Ollama failures never count.

`semantic-index/forget-if-collection-lost!` then resets `:prepared?` and `:embedded`. In `sync!` the update is retried at once, once: the retry prepares the collection and embeds every file. From `semantic_search` the answer says every file will be embedded again; the next freshness check starts the sync. A sync running while `semantic_search` forgets the collection no longer records the files it stores, so they stay stale. `prepare!` now drops any collection that is not current, an empty one too, since it may take vectors of another size.

Known limits:
- A collection recreated empty is noticed only through `:embedded`: if every embedded file had no Search units, nothing tells it from a collection this server never filled.
- Two instances with different models on one collection keep replacing each other's (README says to use different collection names).

Review (code-review of ea060f8) fixed: namespaced ex-data, the vector-size message documented as depending on Qdrant's wording, one place for the layout check, the name `forget-if-collection-lost!`, the answer wording, test exceptions made by the real adapter, emptied and same-size foreign collections, mixed collections, the retry delay and the race with a running sync.
