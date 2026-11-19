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

**Truncation note**:
The closing line of an answer that hit the Output budget, saying how many findings were shown out of how many and how to get the rest.
