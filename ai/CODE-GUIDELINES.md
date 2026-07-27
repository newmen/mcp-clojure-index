# Clojure Code Guidelines

## 1. Clojure Core API over Java Interop

Prefer idiomatic Clojure string functions from `clojure.string` over direct Java method calls on `String`.

**Bad:**
```clojure
(.startsWith trimmed "(defn ")
(.endsWith lc ".clj")
(.toLowerCase ^String s)
```

**Good:**
```clojure
(s/starts-with? trimmed "(defn ")
(s/ends-with? lc ".clj")
(s/lower-case ^String s)
```

Exception: when performance-sensitive loops require `.indexOf` or `.getBytes`, direct Java interop is acceptable but must be annotated with type hints.

## 2. Type Hints on All Java Interop Parameters

Every function parameter that is passed to a Java method must have a `^TypeHint`. This applies to String, File, MessageDigest, WatchService, and all other Java classes.

**Bad:**
```clojure
(defn sha-256 [s] ...)
(defn line-count [s] ...)
```

**Good:**
```clojure
(defn sha-256 [^String s] ...)
(defn line-count [^String s] ...)
```

Include `(set! *warn-on-reflection* true)` at the top of every `ns` that uses Java interop.

## 3. Use `(s/blank? x)` Instead of `(= x "")` / `(= "" x)`

**Bad:**
```clojure
(when (and second (not= "" second))
```

**Good:**
```clojure
(when-not (s/blank? second)
```

## 4. Remove Unused Branch Results

If a cond/branch evaluates to an empty vector `[]`, it is dead code. Return `nil` or restructure to avoid unnecessary allocations.

**Bad:**
```clojure
(if (= :token tag)
  (let [sexpr ...]
    (if (instance? Symbol sexpr) [sexpr] []))
  [])
```

**Good:**
```clojure
(when (= :token tag)
  (let [sexpr ...]
    (when (instance? Symbol sexpr) [sexpr])))
```

## 5. Threading Macros (`->>`, `->`) Over Nested Forms

Use `->>` and `->` for pipelines instead of nested function calls.

**Bad:**
```clojure
(vals (into {} (map (fn [e] [(edge-key e) e]) edges)))
```

**Good:**
```clojure
(->> edges
     (map (fn [e] [(edge-key e) e]))
     (into {})
     (vals))
```

## 6. `(comp pred)` over Anonymous Functions for Predicate Composition

Use `(comp #{:whitespace :newline} n/tag)` instead of `(fn [c] (#{:whitespace :newline} (n/tag c)))`.

**Bad:**
```clojure
(remove (fn [c] (#{:whitespace :newline} (n/tag c))) children)
```

**Good:**
```clojure
(remove (comp #{:whitespace :newline} n/tag) children)
```

## 7. Reuse Extracted Helper Functions

When the same computation appears in multiple places (e.g. parsing a string into a zloc and getting its top-level children), extract it into a `defn-` helper.

**Bad (duplicated in both `top-level-forms-cst` and `extract-form-metadata`):**
```clojure
(let [zloc (z/of-string* source {:track-position? true})
      node (z/node zloc)
      top-children (n/children node)] ...)
```

**Good:**
```clojure
(defn- get-top-children [^String s]
  (let [zloc (z/of-string* s {:track-position? true})
        node (z/node zloc)]
    (n/children node)))
```

## 8. Meaningful Parameter Names Over Single Letters

Function parameters should be descriptive, not single characters or overly abbreviated.

**Bad:**
```clojure
(defn sha-256 [s] ...)
(defn classify-form-type [s] ...)
(defn line-count [s] ...)
```

**Good:**
```clojure
(defn sha-256 [^String encoding-str] ...)
(defn classify-form-type [^String form-str] ...)
(defn line-count [^String form-str] ...)
```

Exception: `(fn [acc sym])` and `(fn [k v])` in single-line reduce/loop bodies are acceptable.

## 9. Avoid False-Friend Variable Names

When the second parameter of a function is not actually the namespace name, do not call it `ns-name`.

**Bad:**
```clojure
(defn- resolve-symbol [sym _ns-name index])
```

**Good:**
```clojure
(defn- resolve-symbol [sym _ns-name index])  ; TODO: use ns-name
```

Use `_` prefixed names with a TODO comment when the parameter is accepted but not yet used.

## 10. Prefer `or` Over Nested `if-let` for Fallback Chains

**Bad:**
```clojure
(if ns-chunk
  (or (extract-form-name (:chunk/source ns-chunk)) "unknown")
  "unknown")
```

**Good:**
```clojure
(or (and ns-chunk (extract-form-name (:chunk/source ns-chunk))) "unknown")
```

## 11. Simplify Redundant `or` in Return Values

**Bad:**
```clojure
(let [ns-name (or (extract-ns-name ...) "unknown")]
```

**Good:**
```clojure
(let [ns-name (extract-ns-name ...)]
```

When `extract-ns-name` already returns `"unknown"` as fallback, the `or` is redundant.

## 12. Unused Binding Names Should Use `_` Prefix or Be Removed

**Bad:**
```clojure
(reduce-kv (fn [acc _ chunks] ...) ...)  ; _ is the file path, not used
```

**Good:**
```clojure
(reduce-kv (fn [acc _file chunks] ...) ...)
```

## 13. Remove Nested Wrapping When the Outer Function is Trivial

When a helper function just wraps another with identical semantics, inline it and remove the wrapper.

## 14. Simplify Redundant Conditional Branches

When `if` branches evaluate to the same value, remove the conditional:

**Bad:**
```clojure
(if condition
  (do-something)
  (do-something))  ; same branch
```

## 15. Consistent Constants Placement

Define `^:private` constants at the top of the namespace, not inline.

**Bad:**
```clojure
(let [cache-max-size 50] ...)
```

**Good:**
```clojure
(def ^:private cache-max-size 50)
```

## 16. Conventions

- **Docstrings on `defn`**: Required for public API functions.
- **Section comments**: Use `;; ---------------------------------------------------------------------------` with centered text to delimit logical groups.
- **Data structure documentation**: Place a block comment above relevant functions showing the map shape (see `graph.clj:8-23`, `symbol_index.clj:10-22`).
- **Testing**: One test file per source file in `test/mcp/`. Use `deftest` with descriptive kebab-case names. Prefer `is (= expected actual))` over `is (thrown? ...)`.
- **Linting**: Always use `clj-kondo` before committing. Fix all warnings.
- **Namespace form**: Alias `clojure.string` as `s`, `clojure.data.json` as `json`, `clj-http.client` as `http`.
- **Private by default**: Functions not part of the public API should be `defn-`.