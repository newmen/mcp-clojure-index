# 02: Batch `find_definition` laid out like `rg --heading`

**What to build:** An agent passes several names to `find_definition` in one call (`names`, an array, replacing `name`; no backward compatibility) and gets one group per distinct name: a header line with the name as asked, the Definition lines under it, a blank line between groups. The header is there for a single name too. `find_usages` gets the same blank line between its groups. See spec `.scratch/agent-feedback-round-3/spec.md`, sections `find_definition` and `find_usages`.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `names: [string]` replaces `name`; the schema and description say so.
- [ ] Each distinct name answers in its own group headed by the name as written; repeated names answer once.
- [ ] A name that is not found says so in its header line with what was searched; an ambiguous name lists its candidates in its group; other names still answer.
- [ ] `doc: "full"` applies to every name; the single-finding docstring Truncation note still works.
- [ ] `limit` and `offset` count Definitions across groups; a page starting inside a group repeats its header; the Truncation note works as today.
- [ ] An empty list answers `no names given`.
- [ ] `find_usages` groups are separated by one blank line; headers and Usage lines are otherwise unchanged.
- [ ] Existing `find_definition` and `find_usages` tests are updated; new tool-seam tests cover one name, several, repeats, not found, ambiguous, `doc: "full"`, paging across groups and the empty list.
