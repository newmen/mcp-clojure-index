# Spec: answers an agent can act on without a second call

Status: ready-for-agent

## Problem

After `agent-usefulness`, agents use the server instead of `grep` for "who uses", "where is it defined" and "what is in this namespace". Each answer still often needs a follow-up `get_source`, `Read` or `grep`:

- Docstring lines are cut mid-sentence where the source wraps the docstring, so the agent calls `get_source` to read one sentence.
- Arglists name the arguments but not their types. When checking the signatures of dozens of functions during a refactoring, the agent calls `get_source` on each one, and it cannot tell pure functions from ones with side effects.
- A Usage line does not show how the target is used. After a signature change, every finding needs a `Read` to see the arguments.
- The only way to read a whole docstring is `get_source`, which also returns the body, and the body can be long.
- Calls with the wrong number of arguments and requires that are no longer used can't be found project-wide. clj-kondo reports them one file at a time.
- The descriptions of the precise tools don't say that edits are visible immediately, so the agent re-checks answers with `grep`.
- `semantic_search` doesn't separate hits from noise on docstrings that are not in English: every hit scores 0.47–0.49. Test fixtures show up among the hits.

## Decisions

See `GLOSSARY.md` (Docstring summary, Type signature, Usage context, Usage warning) and ADR-0004, ADR-0006 and ADR-0008 (written in ticket 09).

### Output budget

- `:output/max-chars` default is 4000 (was 2000). ADR-0004 is updated.
- When a single finding (one Definition with its full docstring) is longer than the budget, it is cut, and the answer ends with a Truncation note `truncated: docstring shown A of B chars`. B is the length of the docstring without the source indentation its continuation lines share, as `doc: "full"` shows it. `offset` cannot help here, so the note has no paging hint.
- `get_source` reads "one finding" as one source line (ADR-0004): a body longer than the budget is paged by line with `offset`, and `truncated: source shown A of B chars` appears only when one line alone is longer than the budget.

### Docstring summary

The Docstring summary replaces "first docstring line" in every list answer: `find_definition`, `namespace_summary`, `semantic_search` and the ambiguity candidates of every tool.

- It is the first sentence of the docstring, with the source line breaks joined into single spaces. The Type signature paragraph is not part of the text the summary is taken from.
- A sentence ends at `.`, `!`, `?` or `…` that is outside backticks and is followed by whitespace or by the end of the docstring. ``e.g. `foo!` bar`` cuts at `e.g.`; this is accepted. `` `set-cached-tc!` `` does not cut.
- There is no length limit.
- When text remains after the first sentence, the summary ends with `(+N chars)`. N is the remaining length after whitespace is collapsed, without the Type signature paragraph.

### Type signature

- `:doc/signature` in `config.edn` selects a built-in strategy. The shipped `resources/config.edn` sets `{:strategy :last-paragraph}`; the server's code defaults leave it out, so a config without the key, or with `nil`, has no signatures. A `:pattern` regex that does not compile or has no capture group is rejected when the config is loaded.
  - `:last-paragraph`: the last paragraph (after a blank line) of a docstring with at least two paragraphs, its lines joined with single spaces. A one-paragraph docstring has no signature, and its paragraph is the summary.
  - `{:strategy :pattern :regex "..."}`: the first group of the regex matched against the whole docstring.
  - A new strategy is a `defmethod` in the server, added by pull request. There is no user code in the config.
- Namespaces have no Type signature.
- The signature is a field of the Definition line after the arglists, shown in `find_definition`, `namespace_summary` and the ambiguity candidates of `get_source` and `find_implementations`. It is not shown in `find_usages` (findings or candidates) or in `semantic_search`.

### `find_definition`

```
find_definition
  name    string, required   simple, alias-qualified or fully qualified;
                             `multi dispatch-value` for a defmethod; a keyword for a spec
  doc     "summary" | "full", default "summary"
  limit   integer            lowers :output/limit only
  offset  integer            first finding of the page
```

| Case | `doc: "summary"` | `doc: "full"` |
|---|---|---|
| no Definition | `not found: X (checked N files, including tests)` | same |
| one Definition | `path:line  kind  ns/name  [arglists]  signature  summary (+N chars)` | `path:line  kind  ns/name  [arglists]`, then the whole docstring (signature included) indented by two spaces with its line breaks; `  (no docstring)` when there is none |
| several (ambiguous) | `ambiguous: K definitions named X, pass a qualified name`, then one line per candidate as for one Definition | the header, then each candidate as for one Definition |

A finding is one Definition with its docstring. `limit` and `offset` count Definitions.

### `find_usages`

- **Usage context** is on by default: up to 80 characters of the source line around the Usage column, with runs of whitespace collapsed, appended to the Usage line. `context: false` turns it off. ADR-0006 is updated.
- **Usage warnings** are appended to the Usage line:
  - `wrong arity: 3 args, takes 1|2` (`takes 2+` for varargs) on a `call` whose argument count matches no arity of the target. Arities come from clj-kondo (`:arity` of the usage, the fixed and varargs arities of the definition).
  - On `require` Usages of a namespace target: `unused` when neither the alias nor any referred name is used in that file, and `unused refer: b, c` when the alias or some referred names are used but `b` and `c` are not. A use is anything that resolves to the namespace: a call or value through the alias, a referred name, `#'`, `::alias/kw` (the only use an `:as-alias` can have), or a fully qualified name. A fully qualified name uses the namespace, so the require is not `unused`, but it does not use a referred name: `:refer [b]` with only `x.y/b` in the file is `unused refer: b`, since the refer is redundant. A bare require (no `:as`, `:as-alias` or `:refer`) is never marked, because it is loaded for side effects; neither is `:refer :all`.
- `warnings_only: true` returns only Usages that carry a Usage warning. When none does, the answer says `no usages of X with a Usage warning (checked N files, …)`.
- The tool description says that `unused` is a hint.

### `semantic_search`

- `include_tests`, default `false`, drops Search units in Test paths. The empty answer says `excluding tests`.
- With the reranker running, when the best relevance is below `:search/min-relevance`, the hits are still returned, after a first line `no confident matches (best 0.49), try rephrasing`. Without the reranker there is no score and no such line. The threshold is calibrated in ticket 10.
- The default config uses multilingual models (ticket 09). Both READMEs describe them and when to choose the code-specific models instead.

### Freshness in descriptions

The descriptions of the precise tools and the server `instructions` say that the index is re-read from changed files (by mtime) before every answer, so edits are visible immediately. This is already how the server behaves (ADR-0005).

### Docstrings of this code base

Before any of the above, every `defn`, `defn-` and `defmacro` in `src/` and `test/` gets a docstring that follows `.claude/rules/docstrings.md`, ending with a Type signature (ticket 01). The server's own code is then the fixture for the summary and signature tests.

## Acceptance

Automated tests. The precise-tool tests run without Qdrant/Ollama in the default `lein test`. As before, every case asserts completeness against a hand-checked expected list and answer size within the budget. Fixtures under `test-resources/` cover:

1. A Russian docstring wrapped over several source lines with a signature as its last paragraph: the summary is the whole first sentence, then `(+N chars)` when more text follows, and the signature is a separate field.
2. A `def` whose signature is a bare type without `->`.
3. A one-paragraph docstring: no signature.
4. A docstring that ends with `.` right before the closing quote (no whitespace after it).
5. `e.g.` and a backticked `foo!` inside a sentence.
6. The `:pattern` strategy and a config without `:doc/signature`.
7. `find_definition doc: "full"` for one Definition, for ambiguous candidates, and for a docstring longer than the budget (Truncation note).
8. Usage context on `call`, `keyword` and `edn` Usages; `context: false`.
9. A call with the wrong arity, including against a varargs arity; `warnings_only`.
10. Requires that are unused, partly unused (`unused refer:`), used only through `::alias/kw`, bare, and with `:refer :all`.
11. `semantic_search include_tests` both ways.
12. `^:integration`, fixtures with Russian docstrings and queries in English and Russian. Each expected Definition is in the top 3. The test must fail with the current models (`vishalraj/nomic-embed-code` + `qllama/bce-reranker-base_v1`) and pass with the chosen multilingual models, together with the existing acceptance case 9 of `agent-usefulness`.
13. `^:integration`: queries with no matching Search unit get the `no confident matches` line, and the queries of case 12 don't.

## Delivery

Vertical slices, test-first (TDD), one ticket each, in this order:

1. Type signatures in this code base's docstrings.
2. Output budget 4000, Docstring summary, freshness in descriptions and `instructions`.
3. Type signature strategies.
4. `find_definition doc: "full"` and the single-finding Truncation note.
5. Usage context.
6. Usage warning: wrong arity, `warnings_only`.
7. Usage warning: unused require and unused refer.
8. `semantic_search include_tests`.
9. Multilingual models: Russian fixtures, measurement, default config, ADR-0008, both READMEs.
10. `:search/min-relevance` and the `no confident matches` line.

## Out of scope

- Unused-require reports without a namespace target (a project-wide list).
- Marking `:refer :all` and bare requires.
- Type signatures in `semantic_search` and `find_usages` lines.
- Search in strings, comments and Markdown: `grep` does this better.
