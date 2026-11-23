# 03: Resolve names as written in a given namespace

**What to build:** `find_definition`, `get_source`, `find_implementations` and `find_usages` take an optional `ns` and resolve names as that namespace's source would. `::k` becomes `:<ns>/k`. `::alias/k` and `alias/name` go through its `:require` aliases. A bare name resolves to its own Definition, then its `:refer`s. Qualified names are unaffected. A name that does not resolve in `ns` answers `not resolvable in <ns>, all candidates:` followed by today's answer without `ns`. An unknown `ns` answers `unknown namespace: <ns>`. See spec `.scratch/agent-feedback-round-4/spec.md`, section "`ns` parameter".

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] One shared `ns` schema field on the four tools: "Resolve names as written in this namespace (`::k`, aliases, :refer)".
- [x] `::k`, `::alias/k`, `alias/name` and bare names resolve in `ns`. Qualified names and keywords are unchanged.
- [x] In batches (`names`, `targets`) `ns` applies to every name. A name that does not resolve gets the fallback line as its group header.
- [x] An unknown `ns` answers only `unknown namespace: <ns>`.
- [x] Without `ns`, every answer is unchanged.
- [x] Tool-seam tests for each tool, including two namespaces with the same alias for different targets.
