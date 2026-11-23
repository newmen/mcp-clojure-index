# Answers contain only what the agent needs for its next step

The first version returned everything it had: full source of every hit, every other definition in the same files, all graph neighbours of the hits, chunk UUIDs, scores and hashes. One semantic query produced 595k characters, and the agent dropped the server entirely. Every answer is now a Compact answer:

- one line per finding: `path:line:col  kind  ns/enclosing-definition` (or the analogous fields for the tool); `find_usages` also shows by default a fragment of up to 80 characters of the source line around the Usage (`context: false` turns it off), because acting on a Usage, e.g. after a signature change, needs the call's arguments, and an opt-in flag would be forgotten;
- paths relative to the project root;
- no function bodies unless explicitly requested through `get_source`;
- no expansion of results with neighbours by file or by call graph;
- no duplicates;
- no internal fields (ids, hashes, raw vector scores); the only score shown is relevance in `semantic_search`;
- an empty result says what was searched (`no usages (checked 214 files, including tests)`), so "not found" is distinguishable from "not searched".

`namespace_summary` is the opposite case for Docstring summaries: its Definition lines show them only with `detail: true`. With every docstring the answer was bigger than grepping the `(def` lines (ADR-0009), and the sentence for one Definition is a `find_definition` call away.

Type signatures, by contrast, are on every `namespace_summary` line by default (amended in agent feedback round 3). An agent reworking a namespace checks the types and `IO` of every function; without signatures it either did not know about `detail` and made a `find_definition` call per function, or got every Docstring summary with them. Signatures are short and the one thing grep cannot give in one line, so they earn their place in the overview; a Definition or project without a Type signature shows none.

This is separate from the Output budget (ADR-0004): the budget caps the size, while this decision defines what is allowed into an answer at all. Do not "enrich" answers with extra context; if the agent needs more, it asks with another call.
