# 01: `namespace_summary` shows Type signatures by default

**What to build:** An agent reworking a namespace calls `namespace_summary` without flags and sees each Definition's Type signature on its line (`line  kind  name  [arglists]  Type signature`), so it can check types and `IO` of every function in one call. `detail: true` now only adds the Docstring summary. A Definition without a Type signature, or a project without a signature strategy, shows no signature field. Header and `required by:` lines are unchanged. See spec `.scratch/agent-feedback-round-3/spec.md`, section `namespace_summary`.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Default `namespace_summary` lines carry the Type signature, the same text `find_definition` shows for that Definition.
- [ ] `detail: true` adds the Docstring summary after the signature; without it there is no Docstring summary.
- [ ] Definitions without a Type signature, and projects without `:doc/signature`, keep `line  kind  name  [arglists]`.
- [ ] The tool description and the `detail` parameter description say what is shown by default and what `detail` adds.
- [ ] ADR-0006 is amended: Type signatures are in the overview by default, Docstring summaries stay behind `detail`, and why.
- [ ] ADR-0009 notes that its `namespace_summary` numbers predate this change and need remeasuring.
- [ ] `GLOSSARY.md` Docstring summary entry still matches (already updated during grilling).
- [ ] Tool-seam tests cover default and `detail: true`, with and without a signature strategy; every answer fits the Output budget.
