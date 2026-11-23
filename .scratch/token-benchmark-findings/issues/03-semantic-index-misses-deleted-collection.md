# 03: the semantic index does not notice a collection deleted by another instance

**Status:** needs-triage

`mcp.semantic-index/sync!` calls `prepare!` only while `:prepared?` is false, and `:embedded` keeps the mtimes of files already stored. If another server instance (another payload format or embedding model) or the user deletes or recreates the Qdrant collection, this instance keeps `:prepared? true`, sees no stale files, and never recreates or refills the collection: `semantic_search` answers from an empty or foreign collection until restart.

Detect it, e.g. when Qdrant answers 404 or the collection's vector size/format marker differs: reset `:prepared?` and `:embedded`, then prepare and re-embed.
