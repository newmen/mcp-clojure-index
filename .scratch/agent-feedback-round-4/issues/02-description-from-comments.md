# 02: A Definition without a docstring is described by the comment above it

**What to build:** A spec, or any other Definition without a docstring, takes its Description from the block of `;;` lines directly above its form, with no blank line in between. The Description fills the Definition's `:doc` in the code index. The Search unit text, lexical matching, the Docstring summary, `doc: "full"` and the Type signature then use it with no changes of their own. A docstring wins over a comment. Namespaces use their docstring only. See spec `.scratch/agent-feedback-round-4/spec.md`, section "Description from comments".

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The `;;` block directly above a form becomes the Description of a Definition without a docstring. Leading semicolons and one space are stripped, and an empty `;;` line separates paragraphs.
- [ ] A comment separated from the form by a blank line does not count.
- [ ] A docstring wins over a comment. A namespace's header comment never becomes its Description.
- [ ] Docstring summary, `doc: "full"` and the Type signature (strategy of the Project config) work on a comment Description.
- [ ] The Search unit text of a comment-described spec contains its Description.
- [ ] Tool-seam tests for each case above. An integration test on `multilingual_project` finds a comment-described spec in the top 3 by a paraphrase of its comment. Acceptance cases 12 and 13 still pass.
- [ ] README: say that comments directly above a form count as its description.
