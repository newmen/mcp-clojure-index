# 05: Hot `:index/exclude` and `:index/include-extensions`

**What to build:** An agent edits `:index/exclude` or `:index/include-extensions` in the Project config, and from its next call the precise tools see the new file set: newly excluded files are gone, newly included ones are analysed. The semantic index follows the same file set: it forgets the Search units of files that left and treats files that joined as stale, so the background sync embeds them (ADR-0003). See spec `.scratch/agent-feedback-round-3/spec.md`, section Project config.

**Blocked by:** 04

**Status:** resolved

- [x] After adding a path to `:index/exclude`, the next `find_definition`/`find_usages`/`namespace_summary` no longer finds its Definitions; after removing it, they are found again.
- [x] Adding an extension to `:index/include-extensions` makes files with it analysed from the next call; removing it drops them.
- [x] The semantic index forgets points of files that left the file set and embeds files that joined it, without rebuilding the collection.
- [x] Always-excluded directories stay excluded whatever the Project config says.
- [x] Server-seam tests cover both keys; semantic-index-seam tests (fake Qdrant) cover forgetting and re-embedding.
