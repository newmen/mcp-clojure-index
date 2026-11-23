# Measuring savings against grep

ADR-0001 says every tool must give a smaller answer than the agent's default, `grep` plus reading files. The savings are measured with `dev/token_benchmark.clj` and a method fixed in advance, so that a rerun on the same code gives the same numbers and the numbers of different projects can be compared.

Decisions:

- **Unit: characters the agent receives.** Tokens depend on the tokenizer and, roughly 3.5–4 characters per token of Clojure, both sides divide by the same factor, so the ratio of characters is the saving in tokens. The baseline is counted in the form agents get it: `rg -n` output lines (`path:line:text`) and Read output with its line-number prefix (7 characters and a tab per line).
- **Two baselines per question.** *Careful* is a disciplined agent: a precise `rg` pattern and Read of only the lines it needs. *Typical* is the Claude Code default: the same `rg`, then Read of every file with a hit, whole. The saving against careful is the conservative number; real sessions lie between the two.
- **The questions are sampled, not chosen.** 20 functions and macros of the project's source (not tests), shuffled with seed 42, give the `find_definition`, `get_source` and `find_usages` questions; every source namespace gives a `namespace_summary` question. Generated code can be left out of the sample (`SKIP-REGEX`), because agents do not ask about it, but `rg` still searches it, as it would in a session.
- **`rg` is held to what the server indexes**: `.clj`, `.cljc`, `.cljs`, `.edn`, without the `:index/exclude` directories. Grep never pays for files the server does not read.
- **What each baseline reads**, per scenario:

| Question | MCP | Careful | Typical |
|---|---|---|---|
| where is `name` defined | `find_definition` | `rg '\(def\S*\s+name(\s\|$)'` + 15 lines from each hit | same `rg` + each hit file |
| source of `ns/name` | `get_source` | same `rg` + the body and 10 lines past it (the end is not known in advance) | same `rg` + the file |
| who uses `ns/name` | `find_usages`, every page | `rg` of the name as a whole symbol, alias-qualified or not, + ±10 lines around each hit (to tell the enclosing definition) | `rg -F name` + each hit file |
| what is in `ns` | `namespace_summary` (default, without `detail`), every page | `rg '^\s*\(def'` in the file + `rg -F ns` for who requires it | the file + `rg -F ns` |
| where is the code that does Y | `semantic_search`, first page | `rg -i` of 1–3 keywords + ±15 lines around the first 5 hits | same `rg` + the 3 files with the most hits |

- **Precision is counted next to size.** For `find_usages`: careful grep hits that are not Usages (*noise*: strings, docstrings, comments, other definitions with the same name) and Usages grep misses. For `semantic_search`: whether the expected Definition is on the answer page, and whether any grep hit lies inside it.
- **Semantic questions are written by hand**, in the agent's words rather than the docstring's, half in Russian, each with the keywords an agent would grep for and the Definition that answers it (`dev/benchmark/<project>.edn`). The keywords are written by someone who knows the code, which favours grep: an agent new to the project guesses worse.

Run from the repository root (Qdrant and Ollama only for the semantic questions; `""` skips them):

```bash
lein run -m clojure.main -i dev/token_benchmark.clj \
  -e '(token-benchmark/-main "/path/to/project" "dev/benchmark/project.edn" "SKIP-REGEX")'
```

## Measurement

On 2026-11-23, the defaults of `resources/config.edn` (`:output/max-chars` 4000, `bge-m3`, `qllama/bge-reranker-v2-m3`). Two projects:

- **this repository**: 40 indexed files, 310 KB, 755 Definitions; 20 source namespaces (22 when `namespace_summary` was remeasured); 12 semantic questions (`dev/benchmark/mcp-clojure-index.edn`).
- **a large project**: 232 indexed files, 1.8 MB, 6535 Definitions, of which one generated protobuf namespace (650 KB, 3400 Definitions) is left out of the sample; 181 namespaces (201 when `namespace_summary` was remeasured); 12 semantic questions.

Saving = 1 − MCP / baseline over all questions of a scenario, median per question in parentheses.

| Question | This repository: careful | typical | Large project: careful | typical |
|---|---|---|---|---|
| `find_definition` | 64% (69%) | 99% (98%) | 77% (81%) | 98% (98%) |
| `get_source` | 50% (54%) | 97% (97%) | 59% (67%) | 96% (94%) |
| `find_usages` | 93% (93%) | 99% (99%) | 95% (90%) | 100% (98%) |
| `namespace_summary` | +36% (+10%) | 90% (90%) | **−2% (−8%)** | 86% (84%) |
| `semantic_search` | 87% (87%) | 97% (97%) | 98% (97%) | 99% (99%) |

Precision:

- `find_usages`: the careful pattern brought 59 (this repository) and 138 (large project) lines over the 20 questions that are not Usages; it missed no Usage of the sampled names in either project.
- `semantic_search`: the expected Definition was on the answer page for 12/12 and 11/12 questions; some grep hit fell inside it for 12/12 and 11/12 (different misses: `token` matched the access token reader by grep but not by the search, `same timestamp` the opposite). Grep "finds" it among the hit lines the agent still has to read: a median of 44 lines (10–240) in this repository and 628 (4–3662) in the large project.

`namespace_summary` is the one answer often bigger than the careful baseline (8 of 22 and 117 of 201 namespaces). The first measurement gave Type signatures and Docstring summaries on every line and was bigger by 67% and 36% (17 of 20 and 122 of 181 namespaces); they are now opt-in (`detail: true`), the namespace's own specs are written `::name`, the `public`/`private` column is gone (a private Definition shows it as its source does: `defn-`, `def  ^:private name`), and the default answer was remeasured on the same day, when the projects had grown to 22 and 201 namespaces. With `detail: true` the same code gives −62% (−109%) and −24% (−20%) against careful (18 of 22 and 122 of 201 namespaces bigger), 75% and 84% against typical. What remains of the excess is information `rg '^\s*\(def'` does not give: specs (`(s/def` does not match `\(def`), protocol methods, the constructors a `defrecord` generates, arglists that start on the next line, and the requiring namespaces. Following ADR-0001, the baseline that answers the question is reading the file, against which it saves 86–90%.

These `namespace_summary` numbers predate Type signatures on every line by default (ADR-0006, agent feedback round 3); the default answer now carries them, and the numbers need remeasuring.

## Consequences

- A tool whose answer is bigger than the careful baseline is reported (`:mcp-bigger`), as ADR-0001 asks.
- The numbers belong to one project: they grow with file size (the typical baseline reads whole files) and with how often names repeat (grep noise). The README quotes them with that caveat.
- The benchmark measures single questions, not sessions. Session savings are smaller: the system prompt, reasoning, edits and test runs cost the same with or without the server.
