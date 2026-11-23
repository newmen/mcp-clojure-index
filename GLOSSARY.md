# Clojure Code Index

An MCP server that answers an agent's questions about a Clojure codebase precisely and in as few tokens as possible, so the agent does not fall back to `grep` and reading whole files.

## Language

### Code model

**Definition**:
A named top-level form that introduces something the code can refer to: a var (`defn`, `def`, `defmacro`, …), a test (`deftest`), a multimethod or its method, a protocol, a record, a spec (`s/def`), or a form defined through a project macro.
_Avoid_: symbol, chunk, form

**Usage**:
A single place in the code where a Definition, a namespace or a keyword is referenced, identified by file, line and column.
_Avoid_: caller, edge, reference, occurrence

**Usage kind**:
How a Usage refers to its target: `call`, `value` (passed as data, e.g. to `map` or `partial`), `var-ref` (`#'ns/name`), `redef` (bound in `with-redefs`), `in-comment` (inside a `comment` form), `require` (a namespace in `:require`), `keyword`, `edn` (a keyword inside an EDN file).
_Avoid_: edge type, usage type

**Enclosing definition**:
The Definition whose body contains a given Usage.
_Avoid_: caller, parent

**Implementation**:
A Definition that fulfils a protocol method or a multimethod dispatch value.

**Target**:
What `find_usages` searches the Usages of: a Definition, a namespace, or a keyword (which needs no Definition).
_Avoid_: query, symbol

**Usage context**:
The fragment of the source line around a Usage, shown next to it so the agent sees how the target is used without opening the file.
_Avoid_: snippet, preview

**Usage warning**:
A mark on a Usage that probably needs a fix: a call whose argument count matches no arity of its target, or a `require` whose alias or referred names the file never uses.
_Avoid_: lint, error, problem

**Search exclusion**:
A path the project keeps out of semantic search, typically generated code, which is never the answer to "where is this done"; the precise tools still see it.
_Avoid_: ignore, skip

**Test path**:
A source directory the project declares for tests; Usages under it are test Usages. When the project declares none, the fallback is `test/` plus every `*_test.clj*` file.

### Answers

**Search unit**:
The piece of code that semantic search embeds and returns: one Definition, or one top-level entry of an EDN map.
_Avoid_: chunk, document

**Output budget**:
The server-wide ceiling on the size of one answer, in items and in characters, that no call can exceed.
_Avoid_: limit (when meaning the ceiling), page size

**Compact answer**:
An answer that contains one line per finding with only what the agent needs for its next step, and nothing else.
_Avoid_: summary, brief output

**Docstring summary**:
The first sentence of a Definition's docstring, as one line even when the source wraps it; it stands for the docstring in every list answer (in `namespace_summary` only with `detail: true`; Type signatures it shows without asking).
_Avoid_: first line, short doc

**Type signature**:
The part of a Definition's docstring that states its types (e.g. `String -> IO ()`), found by the signature strategy the project configures; namespaces have none.
_Avoid_: type, contract, spec (a spec is a Definition)

**Truncation note**:
The closing line of an answer that hit the Output budget, saying how many findings were shown out of how many and how to get the rest.

### Settings

**Project config**:
The settings a project keeps in its own repository, over the server's defaults; the agent may edit them, and they apply from the next answer.
_Avoid_: local config, overrides
