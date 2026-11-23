# Measuring savings against grep

ADR-0001 says every tool must give a smaller answer than the agent's default, `grep` plus reading files. The savings are measured with `dev/token_benchmark.clj` and a method fixed in advance, so that a rerun on the same code gives the same numbers and the numbers of different projects can be compared.

Decisions:

- **Unit: characters the agent receives.** Tokens depend on the tokenizer and, roughly 3.5–4 characters per token of Clojure, both sides divide by the same factor, so the ratio of characters is the saving in tokens. The baseline is counted in the form agents get it: `rg -n` output lines (`path:line:text`) and Read output with its line-number prefix (7 characters and a tab per line).
- **Three baselines per question.** *Careful* is a disciplined agent: a precise `rg` pattern and Read of only the lines it needs. *Typical* reads every file with a hit, whole. *Claude* is what Claude Code actually does, between the two: in its transcripts of the two measured projects 42% of 776 reads of Clojure files took the whole file (`cat`, Read without offset/limit) and the rest a window (`sed -n`, `head`, Read with offset/limit), so the Claude baseline is 0.58 × careful + 0.42 × typical (`whole-file-share`). The README quotes the Claude baseline; careful is the conservative floor.
- **The measured project's Project config applies**, as in the server. With `:doc/signature` (both measured projects set it), every Definition line of `find_definition` and `namespace_summary` carries its Type signature, which the careful baseline gets by reading 15 lines from the definition.
- **Session savings are estimated, not measured**: the share of a session's input tokens that navigation costs, times the per-question saving. `dev/benchmark/session_share.py` reads Claude Code transcripts; since every API request re-reads the context, a tool result costs its size once per later request in the same context (until `/clear` or compaction). Navigation is what the server can answer instead: Grep, Glob, read-only shell searches, Read of a Clojure file the session does not edit afterwards (Edit needs a Read first).
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
python3 dev/benchmark/session_share.py ~/.claude/projects/<the project's transcript directory>
```

## Measurement

On 2026-11-23, the defaults of `resources/config.edn` (`:output/max-chars` 4000, `bge-m3`, `qllama/bge-reranker-v2-m3`) with each project's Project config (`:doc/signature {:strategy :last-paragraph}`). Two projects:

- **this repository**: 45 indexed files, 400 KB; 22 source namespaces; 12 semantic questions (`dev/benchmark/mcp-clojure-index.edn`).
- **a large project**: 222 indexed files, 1.2 MB, of which one generated protobuf namespace is left out of the sample (`SKIP-REGEX`); 181 namespaces; 12 semantic questions, kept with that project.

Saving = 1 − MCP / baseline over all questions of a scenario, median per question in parentheses.

| Question | This repository: Claude | careful | typical | Large project: Claude | careful | typical |
|---|---|---|---|---|---|---|
| `find_definition` | 97% (97%) | 66% (66%) | 99% (99%) | 96% (95%) | 77% (80%) | 98% (98%) |
| `get_source` | 94% (94%) | 50% (57%) | 97% (97%) | 91% (90%) | 59% (67%) | 96% (94%) |
| `find_usages` | 98% (98%) | 94% (93%) | 99% (99%) | 99% (97%) | 95% (90%) | 100% (98%) |
| `namespace_summary` | 72% (73%) | +4% (−40%) | 86% (86%) | 70% (68%) | **−4% (−6%)** | 85% (83%) |
| `semantic_search` | 95% (96%) | 88% (87%) | 97% (98%) | 99% (98%) | 98% (97%) | 99% (99%) |

Both projects together, per question on average (characters / 3.7): `find_definition` 75 tokens against 2,064 with the Claude baseline (96%), `get_source` 107 / 1,617 (93%), `find_usages` 126 / 9,624 (99%), `namespace_summary` 245 / 839 (71%), `semantic_search` 394 / 16,945 (98%); 91% on average over the scenarios, 94% over all questions.

Precision:

- `find_usages`: the careful pattern brought 202 (this repository) and 138 (large project) lines over the 20 questions that are not Usages; it missed no Usage of the sampled names in either project.
- `semantic_search`: the expected Definition was on the answer page for 12/12 and 11/12 questions; some grep hit fell inside it for 12/12 and 11/12 (different misses: `token` matched the access token reader by grep but not by the search, `same timestamp` the opposite). Grep "finds" it among the hit lines the agent still has to read.

`namespace_summary` is the one answer often bigger than the careful baseline (17 of 22 and 100 of 181 namespaces), since every line now carries its Type signature. The excess is information `rg '^\s*\(def'` does not give: Type signatures, specs (`(s/def` does not match `\(def`), protocol methods, the constructors a `defrecord` generates, arglists that start on the next line, and the requiring namespaces. Following ADR-0001, the baseline that answers the question is reading the file, against which it saves 85–86%.

Sessions: 123 Claude Code sessions of the two projects (645M input tokens, 15 of them with the server, which they hardly used: 1% of navigation). Navigation took 16% of the input tokens over all sessions, 13% in the median one (quartiles 7–18%). With the server saving 91–94% of it, a session saves about 12–15%.

## Consequences

- A tool whose answer is bigger than the careful baseline is reported (`:mcp-bigger`), as ADR-0001 asks.
- The numbers belong to one project: they grow with file size (the typical baseline reads whole files) and with how often names repeat (grep noise). The README quotes them with that caveat.
- The benchmark measures single questions; the session estimate assumes the agent asks the server whatever it would otherwise grep for. Session savings are smaller than per-question ones: the system prompt, reasoning, edits and test runs cost the same with or without the server.
