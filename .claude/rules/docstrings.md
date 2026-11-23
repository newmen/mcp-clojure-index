---
paths:
  - "**/*.clj"
  - "**/*.cljc"
---

# Docstrings and type signatures

Every `defn`, `defn-` and `defmacro` has a docstring, in English, in the vocabulary of `GLOSSARY.md`.

- The first sentence says what the function does or returns. The server shows it as the Docstring summary, so it must make sense on its own. A sentence ends at `.`, `!`, `?` or `…` outside backticks, followed by whitespace or by the end of the docstring.
- Mention arguments in backticks only when their meaning isn't obvious from the signature and names.
- The last paragraph, separated by a blank line, is the Type signature in Haskell-like arrow notation. The server's `:doc/signature {:strategy :last-paragraph}` reads it from there.
- A `def` with a docstring ends with its type as the last paragraph, e.g. `Long` or `#{String}`.
- Namespaces have no Type signature.

## Signature notation

- `A -> B -> C`: the arguments in order, then the result. Write one line per arity.
- `[X]` is a sequence, `#{X}` a set, `{K V}` a map used as a dictionary, `& X` a variadic tail.
- `[A B]` is a tuple (fixed-length vector) when two or more element types are listed. `[X]` with one type is always a sequence. Separate elements with commas when an element type has several words: `[Maybe Row, String]`.
- `{:k X :l Y}` is a map with fixed keys. A map that travels between functions or namespaces gets a named type instead.
- `() -> X` is a function without arguments (a thunk). As an argument it is written `(() -> X)`.
- `Maybe X`: the value may be `nil`. `X | Y`: one of the variants.
- `IO X`: the function has side effects (files, network, Qdrant, Ollama, atoms, logging). `IO ()`: no meaningful result. A function without `IO` must be pure.
- Lowercase letters are type variables: `(a -> b) -> [a] -> [b]`.
- A function argument is always written in parentheses with its own arrows: `(a -> b)`. A predicate is `(a -> Boolean)`. Never write `Any`, `Map` or a bare name for a function argument.
- Built-in types need no spec:
  - Values: `String`, `Long`, `Double`, `Boolean`, `Symbol`, `Keyword`, `UUID`, and `Pattern` (a regex).
  - `Map` and `Any`.
  - Type constructors: `Maybe X`, `IO X`, `Atom X` (an atom holding an `X`).
  - JVM classes by their simple name, such as `File` or `OutputStreamWriter`.
- Every other capitalized name is a domain type (`Definition`, `Usage`, `SearchUnit`, `Config`). Each must be defined with `clojure.spec` (`(s/def ::definition ...)`, the type name in PascalCase, the spec key in kebab-case) in its owning namespace. A type that is a `GLOSSARY.md` term uses the term's name. Never use an undefined type name. Use `Map` and `Any` only for truly arbitrary values.
- Prefer destructuring in the arglist to show the map keys a function uses.

```clojure
; bad: undefined types, side effects hidden
(defn- write-points!
  "Writes points: Map -> Map."
  [client points]
  ...)

; good
(defn usages
  "Every Usage of `target` in `index`, in file order.

  CodeIndex -> Target -> [Usage]"
  [index target]
  ...)

(defn- upsert-units!
  "Stores the embeddings of `units` in the Qdrant collection.

  Config -> [SearchUnit] -> IO ()"
  [config units]
  ...)
```
