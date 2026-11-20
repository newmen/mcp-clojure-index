# 02: Output budget 4000, Docstring summary, freshness in descriptions

**What to build:** `:output/max-chars` defaults to 4000. Every list answer (`find_definition`, `namespace_summary`, `semantic_search`, ambiguity candidates of every tool) shows the Docstring summary instead of the first docstring line: the first sentence with source line breaks joined, ending at `.`, `!`, `?` or `…` outside backticks followed by whitespace or the end of the docstring, with no length limit, followed by `(+N chars)` when text remains (whitespace collapsed). Until ticket 03 there are no signatures, so the whole docstring counts. The descriptions of the precise tools and the server `instructions` say that the index is re-read from changed files before every answer, so edits are visible immediately. See spec, "Output budget", "Docstring summary", "Freshness in descriptions".

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Default `:output/max-chars` is 4000 in `config.clj` defaults, `resources/config.edn` and both READMEs.
- [ ] A Russian docstring wrapped over several source lines gives its whole first sentence.
- [ ] `(+N chars)` is present exactly when text remains after the first sentence.
- [ ] A sentence ending in `.` right before the closing quote ends there; `e.g.` cuts; a backticked `foo!` does not.
- [ ] Tool descriptions and `instructions` state the freshness guarantee.
- [ ] Answers stay within the budget (acceptance cases 1, 4, 5).
