# 05: specs of another namespace's keyword are missing from `namespace_summary`

**Status:** resolved

`spec-definitions` in `mcp.code-index` takes a spec Definition's `:ns` from its keyword's namespace (clj-kondo's `:ns` of the keyword), not from the namespace of the file where `s/def` is written. So `(s/def :other.ns/x ...)` in `app.core` belongs to `other.ns`. `namespace_summary` of `app.core` leaves it out, and `namespace_summary` of `other.ns` answers `not found` when no file declares that namespace. The spec does not show up in any summary. `find_definition` still finds it by its keyword.

Real case, the large project (2026-11-23): a generated protobuf namespace `api.contract.v1` has 784 `s/def` forms, and 586 of them use message-namespaced keywords such as `:api.contract.v1.Status/id`. None of the 586 is in the summary of `api.contract.v1`, and `namespace_summary` of `api.contract.v1.Status` answers `not found`.

Decide:

- a spec Definition gets the namespace of its file in `:ns`, and the keyword keeps its own namespace in `:name`. `namespace_summary` (`name-as-written`) then has to write such a spec in full; now it writes every spec as `::name`, since it lists only its own. Check what else reads `:ns` of a spec: `find_definition`/`keyword-matcher` (`::uuid`, `::c/uuid`), `find_usages`, `semantic_search` units, `enclosing-definition`;
- or keep the current attribution and say in the `namespace_summary` description that specs of other namespaces' keywords are not listed.

The first option makes summaries of generated namespaces like `contract/v1.cljc` bigger. That namespace is left out of the ADR-0009 sample, but an agent can still ask about it.

## Comments

Implemented the first option. A spec Definition's `:ns` is now the namespace of its file (clj-kondo's `:from` of the keyword), as the Definition shape in `mcp.code-index` already documented; `:name` keeps the keyword. `namespace_summary` writes a spec of the namespace's own keyword as `::name` and any other keyword in full (`:other.ns/name`, `:name`), and its description says so. Nothing else changed: `keyword-matcher`, `usages :spec`, `qualified-name` (so `find_usages`, `semantic_search` units, `enclosing-definition`) read the spec's namespace from its keyword, not from `:ns`.

Known limit, not changed: `::uuid` matches a spec of any namespaced keyword named `uuid`, so a `:Msg/uuid` spec makes `::uuid` ambiguous with `:app.core/uuid`. That was already so before this change.

Review fixed: duplicated fixture calls in the tests became helpers (`app-core-lines`, `definitions-by-name`), and `find_definition` has a test for the spec of a foreign keyword.
