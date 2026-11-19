# Answers contain only what the agent needs for its next step

The first version returned everything it had: full source of every hit, every other definition in the same files, all graph neighbours of the hits, chunk UUIDs, scores and hashes. One semantic query produced 595k characters, and the agent dropped the server entirely. Every answer is now a Compact answer:

- one line per finding: `path:line:col  kind  ns/enclosing-definition` (or the analogous fields for the tool);
- paths relative to the project root;
- no function bodies unless explicitly requested through `get_source`;
- no expansion of results with neighbours by file or by call graph;
- no duplicates;
- no internal fields (ids, hashes, raw vector scores); the only score shown is relevance in `semantic_search`;
- an empty result says what was searched (`no usages (checked 214 files, including tests)`), so "not found" is distinguishable from "not searched".

This is separate from the Output budget (ADR-0004): the budget caps the size, while this decision defines what is allowed into an answer at all. Do not "enrich" answers with extra context; if the agent needs more, it asks with another call.
