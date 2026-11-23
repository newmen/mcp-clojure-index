# Spec: query wording hints, Descriptions from comments, resolving names in a namespace

Status: ready-for-agent

## Problem Statement

The fourth round of feedback comes from an agent using the server on a large project whose docstrings are in Russian. It has 3114 Search units, 190 of them specs. The agent now uses the precise tools instead of `grep` almost always. It uses `semantic_search` in about 90 % of cases when it writes queries in the docstrings' language, and in about 30 % when it writes in English. It raised three points:

1. **Cross-language search.** English queries against Russian docstrings miss. The agent suggests multilingual models or translating the query into the project's language.
2. **Specs outrank described code on weak matches.** A spec cannot have a docstring, so its Search unit is just a name and a form. When nothing matches well, the spec wins on its name alone.
3. **Ambiguous `::k` and aliases.** `find_definition ::k`, for a spec `::k` defined in two namespaces, cannot tell which one is meant and returns every candidate. Two namespaces that use the same alias for different targets cannot be told apart at all.

Measured on that project on 2026-11-23 (models of ADR-0008, `:search/top-k` 100), point 1 is mostly a misdiagnosis:

- The models are already multilingual (`bge-m3`, `bge-reranker-v2-m3`, ADR-0008).
- The agent's English queries were not translations of the Russian ones. They asked for something else:
  - the first named the key of a cache by an identifier the code does not use for it. The expected Definition is not among the 100 vector candidates and scores 0.01. With the identifier its docstring uses, it is second (0.56);
  - the second asked for a purpose that no docstring of the expected code mentions, in any language.
- English translations that keep the docstrings' words find the right code:

  | Query | Result |
  |---|---|
  | the agent's Russian query, in the docstrings' words (control) | expected Definition 1st, 0.98 |
  | an English translation of it | 1st, 0.83 |
  | an English translation of the second, naming the steps its docstrings name | all three expected Definitions in the top 3, 0.96–0.99 |

  A query in another language costs about 0.15 of relevance. A query in other words misses completely.

For point 2, every spec of that project has no docstring. Many of them have a `;;` comment block right above the form that says what they are, and the index ignores it. Specs win only on weak queries, whose best hit is below `:search/min-relevance` and which already start with `no confident matches`. On strong queries the top 3 is right.

## Solution

- Do not change the models and do not translate queries. Instead, tell the agent how to word a query:
  - the `semantic_search` description gets one sentence: describe what the code does in the words its docstrings would use, not in the identifiers you expect; a query in the docstrings' language matches best;
  - the `no confident matches` line ends with `try rephrasing in the docstrings' words`. When most Descriptions are written in a non-Latin script, it adds `(they are mostly in <Script>)`.
- A Definition without a docstring gets its Description from the block of `;;` comment lines directly above its form. The Description stands wherever a docstring stands today: the Search unit text, lexical matching, the Docstring summary, `doc: "full"`, the Type signature. Scores are not penalised by kind, and `semantic_search` gets no `kinds` parameter.
- `find_definition`, `get_source`, `find_implementations` and `find_usages` get an optional `ns` parameter: resolve names as written in that namespace.
- ADR-0008 records the cross-language measurement, so the next report like this can be answered by a link.

## User Stories

1. As an agent, I want the `semantic_search` description to tell me to describe code in the words its docstrings would use, so that I do not search by the identifiers I imagine.
2. As an agent, I want the description to say that a query in the docstrings' language matches best, so that I switch language when I can.
3. As an agent, I want the `no confident matches` line to tell me to rephrase in the docstrings' words, so that the hint comes at the moment it is useful.
4. As an agent on a project whose docstrings are mostly in a non-Latin script, I want the `no confident matches` line to name that script, so that I know which language to rephrase in without opening a hit.
5. As an agent on a project with mostly Latin-script docstrings, I want the line without the script part, so that I get no noise.
6. As an agent, I want a spec with a `;;` comment block right above it to be found by the meaning of that comment, so that specs compete on what they are for, not on their names.
7. As an agent, I want any Definition without a docstring (`def`, `defonce`, `s/def`, `defmethod`, …) to take its Description from that comment block, so that projects that document with comments are searchable.
8. As an agent, I want a section header comment separated from the form by a blank line not to count, so that `;; --- Helpers ---` never becomes a Description.
9. As an agent, I want a docstring to win over a comment when a Definition has both, so that the explicit Description is the one shown.
10. As an agent, I want `namespace_summary` with `detail: true`, `find_definition`, `semantic_search` and the other list answers to show the Docstring summary of a comment-described Definition, so that its purpose is visible without `get_source`.
11. As an agent, I want `doc: "full"` to show the whole comment block of a comment-described Definition, as for a docstring, so that I read it in one call.
12. As an agent, I want the Type signature strategy applied to a comment Description too, so that projects that put `T -> IO ()` in comments get signatures.
13. As an agent, I want to pass `ns` to `find_definition` so that `::k`, `alias/name` and bare names resolve as they would in that namespace's source.
14. As an agent, I want the same `ns` on `get_source`, `find_implementations` and `find_usages`, so that a name I resolved once means the same thing in every tool.
15. As an agent, I want a name that does not resolve in the given `ns` to say `not resolvable in <ns>, all candidates:` and still list the candidates found without it, so that a wrong `ns` costs me no extra call.
16. As an agent, I want an unknown `ns` to answer with an error line, so that a typo is not mistaken for a missing name.
17. As an agent, I want answers without `ns` unchanged, so that nothing I rely on today breaks.

## Implementation Decisions

See `GLOSSARY.md` (Description, Docstring summary, Type signature) and ADR-0001, ADR-0004, ADR-0006, ADR-0008.

### Query wording hints

- `semantic_search` description: one added sentence, about: "Describe what the code does in the words its docstrings would use, not the identifiers you expect; a query in the docstrings' language matches best."
- The confidence line becomes `no confident matches (best 0.14), try rephrasing in the docstrings' words`, plus ` (they are mostly in Cyrillic)` when the condition below holds.
- Script detection: over the Descriptions of the Search units being searched, the share of Descriptions whose letters are mostly in one non-Latin Unicode script (`Character$UnicodeScript`). When that share is above one half, the line names that script in English (`Cyrillic`, `Greek`, `Han`, …). Detection runs only when the line is printed.
- The server's `initialize` instructions are unchanged.

### Description from comments

- A Definition's Description is its docstring. When it has none, the Description is the contiguous block of lines starting with `;;` directly above the first line of its form, with no blank line in between. Each line loses its leading semicolons and one space. The lines are joined as docstring lines are, and an empty `;;` line separates paragraphs.
- Namespaces keep using their docstring only, so a license header above `(ns …)` never becomes a Description.
- The Description fills the Definition's `:doc` where the code index builds Definitions. Search units, lexical matching, Docstring summary, `doc: "full"` and the Type signature then take it without changes of their own.
- A comment-described Definition's Search unit text changes, so its embedding is recomputed once. No collection rebuild is needed.
- No score penalty by kind and no `kinds` parameter for `semantic_search`: the reranker's score stays a relevance, which `:search/min-relevance` relies on (ADR-0008).

### `ns` parameter

- An optional string `ns` on `find_definition`, `get_source`, `find_implementations` and `find_usages`, with one shared schema description: "Resolve names as written in this namespace (`::k`, aliases, :refer)".
- With `ns`:
  - `::k` → `:<ns>/k`;
  - `::alias/k` and `alias/name` → through that namespace's `:require` aliases;
  - a bare name → that namespace's own Definition, then its `:refer`s;
  - an already qualified name or keyword is unaffected.
- In `find_definition` and `find_usages` the `ns` applies to every name of the batch.
- A name that does not resolve in `ns` gets the line `not resolvable in <ns>, all candidates:`, then the answer it gets today without `ns`. In a batch the line is that name's group header.
- An `ns` that is not a namespace of the index answers `unknown namespace: <ns>` and nothing else.

### ADR-0008

- New section "Cross-language queries on a real project": the measurement from the Problem Statement, without naming the project. Conclusion: with the multilingual pair, the language of the query costs about 0.15 of relevance; the wording decides whether the hit is found at all. Hence the hint and no query translation.

## Testing Decisions

- Good tests check what the agent receives: the answer text for given arguments on a given fixture project.
- **Tool seam** (`tool/call` with a ToolState, fake search services as the existing `semantic_search` tests use):
  - the `no confident matches` line with and without the script part, on a fixture with Cyrillic Descriptions (`multilingual_project`) and one with English ones;
  - a spec and a `def` with a comment block, a header separated by a blank line, a Definition with both docstring and comment, and a namespace with a header comment, each checked through the Docstring summary in `find_definition`, through `doc: "full"` and through a Type signature in a comment;
  - `ns` in all four tools: `::k`, `::alias/k`, `alias/name`, bare name, a name that does not resolve (the line and the fallback), an unknown `ns`, a batch with mixed outcomes.
- **Search unit seam**: the Search unit text of a comment-described spec contains its Description.
- **Integration** (`^:integration`, real Qdrant, Ollama and reranker): on `multilingual_project` a comment-described spec is in the top 3 for a query paraphrasing its comment. Acceptance cases 12 and 13 still pass.

## Out of Scope

- Changing the embedding model or the reranker.
- Translating queries, and a `:search/language` setting.
- Score penalties by Definition kind, and a `kinds` filter for `semantic_search`.
- Renaming Docstring summary.
- The `doc: "full"` line not repeating the Type signature. The feedback itself notes this is correct.
- Comments other than the block directly above a form (trailing comments, `#_`, `(comment …)`).

## Further Notes

- After the change, rebuild the jar and restart the agent's session on the user's project. Otherwise the next round of feedback judges an old build again.
- Acceptance checklist, run by the owner on the large project from the feedback (queries and expected Definitions as in the measurement above):
  1. The English translation of the first query: the expected Definition first.
  2. The English translation of the second query: all three expected Definitions in the top 3, above the threshold.
  3. A spec described by a comment block: found in the top 3 by a query paraphrasing its comment, and its Docstring summary shown by `find_definition`.
  4. Control, the Russian query: the expected Definition first, about 0.98.
  5. `find_definition` of a `::k` spec defined in two namespaces, with `ns` set to one of them: exactly one Definition.
