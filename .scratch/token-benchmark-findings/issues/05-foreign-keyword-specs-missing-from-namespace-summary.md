# 05: specs of another namespace's keyword are missing from `namespace_summary`

**Status:** needs-triage

`spec-definitions` in `mcp.code-index` takes a spec Definition's `:ns` from its keyword's namespace (clj-kondo's `:ns` of the keyword), not from the namespace of the file where `s/def` is written. So `(s/def :other.ns/x ...)` in `app.core` belongs to `other.ns`. `namespace_summary` of `app.core` leaves it out, and `namespace_summary` of `other.ns` answers `not found` when no file declares that namespace. The spec does not show up in any summary. `find_definition` still finds it by its keyword.

Real case, the large project (2026-11-23): a generated protobuf namespace `api.contract.v1` has 784 `s/def` forms, and 586 of them use message-namespaced keywords such as `:api.contract.v1.Status/id`. None of the 586 is in the summary of `api.contract.v1`, and `namespace_summary` of `api.contract.v1.Status` answers `not found`.

Decide:

- a spec Definition gets the namespace of its file in `:ns`, and the keyword keeps its own namespace in `:name`. `local-name` already writes such a spec in full and the file's own specs as `::name` (issue 01). Check what else reads `:ns` of a spec: `find_definition`/`keyword-matcher` (`::uuid`, `::c/uuid`), `find_usages`, `semantic_search` units, `enclosing-definition`;
- or keep the current attribution and say in the `namespace_summary` description that specs of other namespaces' keywords are not listed.

The first option makes summaries of generated namespaces like `contract/v1.cljc` bigger. That namespace is left out of the ADR-0009 sample, but an agent can still ask about it.
