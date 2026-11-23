# Spec: signatures in namespace overviews, batch definitions, project settings

Status: resolved

## Problem Statement

The third round of feedback from an agent using the server on a large real project (232 files, one generated protobuf namespace of about 10 000 lines) says the precise tools have replaced `grep` for "who calls", "where is it defined" and "show the body". The remaining gaps:

- When an agent reworks a namespace, it checks every function's types and side effects (`IO`). `namespace_summary` shows only names and arglists unless asked with `detail: true`, and then it also adds a sentence per Definition. An agent that does not know about the flag makes 30 `find_definition` calls instead of one overview. Without signatures the overview is no better than grepping the `(def` lines.
- `find_definition` takes one name. Looking up the signatures of five functions that turned up in `find_usages` takes five calls that return one line each.
- `semantic_search` warns `no confident matches` only when the best hit scores below 0.05. A query whose best hit scores 0.15 is a weak answer (a good one scores 0.95), but the agent gets no warning and spends `get_source` calls on random hits.
- Generated code (a protobuf namespace) shows up in `semantic_search` hits, because it contains more of the domain's words than any hand-written namespace. Generated code is never the answer to "where is this done".
- All settings live in one server config shared by every project, so a project-specific setting (which paths are generated, how docstrings state Type signatures, which directories to leave out) can only be added by editing that shared file and restarting the server. The agent cannot add an exclusion itself.

Part of the feedback is stale: the agent's session ran an older build of the server, from before `include_tests`, the Docstring summary in tool descriptions and the namespace docstring in the `namespace_summary` header. Those items are not part of this spec.

## Solution

- `namespace_summary` shows each Definition's Type signature by default. `detail: true` now only adds the Docstring summary.
- `find_definition` takes a list of names and answers with one group per name, laid out like `rg --heading`: the name as asked, the Definition lines under it, a blank line between groups. `find_usages` gets the same blank line between its groups.
- The default `:search/min-relevance` becomes 0.3.
- A project may keep a Project config, `.clojure-index.edn` at its root, which overrides any key of the server config. The server re-reads it before every answer. Keys that shape answers or the index apply from the next answer. Keys that set up connections apply only after a restart, and answers say so. A broken Project config does not stop the server: answers name the file and the problem and keep the previous settings.
- A new setting, `:search/exclude`, lists Search exclusions: paths kept out of semantic search and its embeddings. The precise tools still see them.
- The server's instructions tell the agent, in two or three lines, that the Project config exists, what it can hold, and that an edit applies at once, so the agent can add a Search exclusion itself.

## User Stories

1. As an agent reworking a namespace, I want `namespace_summary` to show every Definition's Type signature without extra flags, so that I can check types and side effects of all functions in one call.
2. As an agent, I want the Type signature in `namespace_summary` to be the same text that `find_definition` shows, so that I do not have to reconcile two views of one Definition.
3. As an agent, I want `namespace_summary` to leave out Docstring summaries by default, so that the overview stays small and I can fetch the sentence for one Definition with `find_definition`.
4. As an agent, I want `detail: true` in `namespace_summary` to add the Docstring summaries, so that I can still read every sentence in one call when I need it.
5. As an agent on a project whose Project config sets no Type signature strategy, I want `namespace_summary` lines to be unchanged (name and arglists), so that nothing appears that the project cannot provide.
6. As an agent, I want the `namespace_summary` description to say that Type signatures are shown by default and what `detail` adds, so that I choose the right call without trying it.
7. As an agent, I want to pass several names to `find_definition` in one call, so that I can look up the signatures of the functions `find_usages` turned up without a call per name.
8. As an agent, I want each name of a batch to answer in its own group headed by the name as I wrote it, so that I can tell which lines answer which name.
9. As an agent, I want a group header even when I pass one name, so that the answer has one shape and I never special-case it.
10. As an agent, I want a name that is not found to say so in its group header, together with what was searched, so that one missing name does not hide the others.
11. As an agent, I want an ambiguous name to list its candidates in its own group, so that I can pick one and the other names still answer.
12. As an agent, I want repeated names in a batch to answer once, so that the answer carries no duplicates.
13. As an agent, I want `doc: "full"` to apply to every name of a batch, so that I can read several whole docstrings in one call.
14. As an agent, I want `limit` and `offset` to count Definitions across all groups, so that paging a large batch works like paging any other answer.
15. As an agent, I want a page that starts in the middle of a group to repeat that group's header, so that every page says which name its lines answer.
16. As an agent, I want an empty list of names to answer `no names given`, so that a malformed call is distinguishable from an empty result.
17. As an agent, I want `find_usages` groups to be separated by a blank line like `find_definition` groups, so that both batch tools read the same way.
18. As an agent, I want `semantic_search` to start with `no confident matches` whenever the best hit scores below 0.3, so that I rephrase the query instead of opening random hits.
19. As an agent, I want the weak hits still listed after that warning, so that I can see what the search found.
20. As a project owner, I want to change the confidence threshold in the Project config, so that I can tune it for my project's language and models.
21. As a project owner, I want to keep a Project config in my repository, next to my code, so that the project's settings travel with it and do not touch the shared server config.
22. As a project owner, I want any key of the server config to be allowed in the Project config, so that I can tune whatever my project needs without waiting for the server to allow it.
23. As a project owner, I want a Project config value to replace the server's value whole, lists included, so that I can tell what applies by reading one file.
24. As a project owner, I want the always-excluded directories (`.git`, `target`, …) excluded whatever my Project config says, so that a short exclusion list cannot make the server index build output.
25. As a project owner, I want the server config to define the default of every key, so that a project without a Project config works as before.
26. As a server owner, I want this repository's own settings (its excluded directories, its Type signature strategy) moved out of the shared server config into this repository's Project config, so that the shared config holds only defaults.
27. As an agent, I want to add a Search exclusion by editing the Project config, so that I can drop generated code from my own search results without asking the user to restart anything.
28. As an agent, I want an edit of the Project config to apply from my next call, so that I can check the effect right away.
29. As an agent, I want a changed `:index/exclude` or `:index/include-extensions` to change which files the precise tools see from my next call, so that the index and the settings never disagree.
30. As an agent, I want a newly excluded path's Search units removed from semantic search, and an un-excluded path's files embedded again, so that the search matches the settings without rebuilding the whole collection.
31. As an agent, I want files under a Search exclusion to stay visible to `find_definition`, `find_usages`, `get_source`, `namespace_summary` and `find_implementations`, so that precise questions about generated code still get answers.
32. As an agent, I want Search exclusions written like `:index/exclude` (a regex on the path relative to the project root, `.` matching only a dot), so that I learn one syntax.
33. As an agent, I want a change to a key that needs a restart (Qdrant, Ollama, reranker, transport, `:search/top-k`, `:search/re-rank-top`, `:qdrant/collection`, `:embedding/cache-dir`) to be reported in my answers instead of applied silently or ignored silently, so that I know to ask for a restart.
34. As an agent, I want an unreadable or invalid Project config to make answers start with `project config error in .clojure-index.edn: <reason>; using previous settings`, so that I see which file is broken and why, and can fix it.
35. As an agent, I want an unknown key in the Project config treated as an error, so that a typo such as `:search/exlude` does not silently do nothing.
36. As an agent, I want the server to keep answering on the previous settings while the Project config is broken, so that one bad edit does not take the server away.
37. As a project owner, I want a Project config that is broken from the server's start to fall back to the server config alone, so that the server still starts.
38. As a server owner, I want an invalid server config to stop the server at start, naming the file by its absolute path, so that a misconfigured server is never half running.
39. As an agent, I want the server's instructions to tell me in two or three lines where the Project config is, what it may hold and that edits apply at once, with a generic example such as excluding a generated `com/google/protobuf` tree, so that I can add an exclusion without reading the README.
40. As an agent, I want the `semantic_search` description to say that generated code can be left out with `:search/exclude` in the Project config, so that I think of it when generated code pollutes my hits.
41. As a project owner, I want the README to describe the Project config in full (keys, which apply at once and which after a restart, error behaviour), so that I can set it up by hand.

## Implementation Decisions

See `GLOSSARY.md` (Type signature, Docstring summary, Search exclusion, Project config) and ADR-0004, ADR-0005, ADR-0006, ADR-0010.

### `namespace_summary`

- Each Definition line is `line  kind  name  [arglists]  Type signature`; with `detail: true`, `  Docstring summary` follows. A Definition without a Type signature (or a project without a signature strategy) has no signature field.
- The header line (`ns name  path  Docstring summary`) and the `required by:` line are unchanged.
- The tool description and the `detail` parameter description are updated.
- ADR-0006 is amended: the overview carries Type signatures by default, because they are the one thing grep cannot give in one line and they are short; Docstring summaries stay behind `detail`.
- ADR-0009 gets a note that its `namespace_summary` numbers predate this change and need remeasuring. The owner runs the benchmark after the change; this spec does not.

### `find_definition`

- The `name` parameter is replaced by `names`, an array of strings, like `targets` of `find_usages`. No backward compatibility.
- Each distinct name answers in one group: a header line with the name as asked, then its Definition lines (or, with `doc: "full"`, each Definition head with its indented docstring). The header is there for one name too.
- A name that is not found or is ambiguous says so in its header line (e.g. `stacs/foo: not found (checked 232 files)`, or the ambiguity header followed by the candidates), as the single-name answer does today.
- Groups are separated by one blank line.
- `limit` and `offset` count Definitions across all groups; a page that starts inside a group repeats that group's header. The Truncation note and the single-finding docstring note (ADR-0004) work as today.
- `get_source` and `find_implementations` keep taking one name.

### `find_usages`

- Groups are separated by one blank line. Header text (`app.core/get-uuid: 13 usages`) and Usage lines are unchanged.

### `semantic_search`

- The default `:search/min-relevance` is 0.3. The `no confident matches` line and the hits after it are unchanged.
- Files whose relative path matches a Search exclusion are not embedded and are never hits. They are dropped from both the vector candidates and the lexical candidates.
- The description adds one sentence about `:search/exclude` in the Project config.

### Project config

- The Project config is `.clojure-index.edn` at the project root (`:index/root-path`). It is optional.
- Any key of the server config is allowed. Its value replaces the server config's value whole. The server config defines the default of every key. The always-excluded directories are added to `:index/exclude` after the merge, as today. An unknown key is an error.
- New key `:search/exclude`: a vector of patterns with the syntax of `:index/exclude`, matched against paths relative to the project root. Default `[]`.
- The server config shipped in the repository keeps only defaults: `:index/exclude []`, `:search/exclude []`, `:search/min-relevance 0.3` and no `:doc/signature`. This repository's own `["dev" "test-resources"]` and `:doc/signature {:strategy :last-paragraph}` move into this repository's Project config.
- Keys fall into two groups:
  - **hot**: `:index/include-extensions`, `:index/exclude`, `:search/exclude`, `:search/min-relevance`, `:output/limit`, `:output/max-chars`, `:doc/signature`. They apply from the next answer.
  - **cold**: every other key (Qdrant, Ollama, reranker, transport, `:search/top-k`, `:search/re-rank-top`, `:qdrant/collection`, `:embedding/cache-dir`). They are read at server start only.
- Before every `tools/call`, next to the freshness check of the code index (ADR-0005), the server compares the Project config's modification time (and its presence) with the last read. If it changed:
  - it re-reads and validates the file;
  - a valid file becomes the current settings for its hot keys;
  - a change of `:index/exclude` or `:index/include-extensions` makes the code index reconcile its file set: newly excluded files are dropped, newly included ones are analysed;
  - a change of `:search/exclude` makes the semantic index forget the Search units of newly excluded files and treat newly un-excluded files as stale, so the background sync embeds them (ADR-0003);
  - a changed cold key is not applied; while the file's value differs from the running one, answers end with a line `.clojure-index.edn: <key> changes after a server restart` (one line naming every such key);
  - an invalid or unreadable file keeps the previous settings, and while it stays so, answers start with `project config error in .clojure-index.edn: <reason>; using previous settings`.
- At start, the Project config is read the same way. If it is invalid at start, the server runs on the server config alone and reports the error the same way. An invalid server config still stops the server, and the message names its absolute path.
- Validation covers what the server config validation covers today (signature pattern compiles and has a group, extensions are a collection, …), plus unknown keys and patterns that do not compile.
- The paths in the error and restart lines are relative to the project root.

### Server instructions and README

- The `initialize` instructions gain two or three lines: the Project config file, that it overrides the server config, that edits apply from the next answer (connection settings after a restart), and one example of a Search exclusion with a generic generated-code path (a `com/google/protobuf` tree or similar, not a path from a real user project).
- The README describes the Project config: location, allowed keys, hot and cold keys, replacement semantics, error behaviour, and a full example.

## Testing Decisions

- Good tests check what the agent receives: answer text for given arguments on a given project, and what reaches Qdrant. They do not check how settings are merged or cached.
- **Tool seam** (`tool/call` with a ToolState, as in the existing tool tests): `namespace_summary` lines with and without `detail`; `find_definition` with one name, several names, repeats, unknown, ambiguous, `doc: "full"`, paging across groups with a repeated header, empty list; blank lines between `find_usages` groups; `semantic_search` with a best hit under 0.3 and with Search exclusions (fake search services, as the existing `semantic_search` tests do). These tests take a fully built config and do not know a Project config exists.
- **Server seam** (the `tools/call` handling in the server, one level above the tool, where the freshness check runs): a temporary project with a Project config. Call, edit the Project config, call again and see the edit applied (a hot key such as `:output/limit` or `:index/exclude`); break the file and see the `project config error in .clojure-index.edn: …` line and the previous behaviour; change a cold key and see the restart line; delete the file and get the server config's behaviour back; a Project config invalid from the start. Merging is tested only through this seam.
- **Semantic index seam** (sync against a fake Qdrant, as the existing semantic index tests): excluded files are never embedded; adding an exclusion removes their points; removing it makes them stale and the next sync embeds them.
- The existing config tests keep covering loading the server config and its defaults, including the new `:search/exclude` and the 0.3 threshold.
- Test projects live in fixture directories like the existing ones; tests that need a real Qdrant or Ollama stay `^:integration`.
- The shared test config of this repository must now include this repository's Project config, so the existing tests keep their excluded directories and Type signatures.

## Out of Scope

- Items from the feedback that the current build already provides: `include_tests` in `semantic_search`, "Docstring summary" in tool descriptions, the namespace docstring in the `namespace_summary` header.
- Verifying freshness of the index after edits on the user's project: the owner checks it.
- Running the token benchmark and updating ADR-0009's numbers: the owner runs it after the change.
- Choosing the threshold from benchmark data: the threshold is 0.3.
- Batch input for `get_source` and `find_implementations`.
- Detecting generated code automatically.
- Excluding by namespace name rather than by path.
- Applying cold keys without a restart (switching models, Qdrant or transport in a running server).

## Further Notes

- After the change the jar must be rebuilt and the agent's session on the user's project restarted, otherwise the next round of feedback again judges an old build.
- The acceptance checklist from the feedback, run by the owner on that project: `namespace_summary` of a 32-function namespace shows signatures within about 3 000 characters and without truncation; `find_definition` with two names from different namespaces answers in two groups in one call; a `semantic_search` query from the feedback either finds the expected Definitions in the top five or warns, and shows no generated protobuf namespace once it is excluded in that project's Project config.
