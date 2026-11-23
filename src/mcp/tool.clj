(ns mcp.tool
  "Tool registry. Each tool lives in its own namespace under `mcp.tools.*`
   and registers itself with one `defmethod` of `spec` and one of `call`,
   both dispatching on the tool name as a keyword (`:find_definition`).
   `mcp.tools` requires every tool namespace and serves `tools/list` and
   `tools/call` from these multimethods.

   Handler state shape (third argument of `call`):

   {:config     resolved config (see mcp.config)
    :code-index the precise index (see mcp.code-index), current at call time
    :semantic-index the SemanticIndex of semantic_search (see mcp.semantic-index)}"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]))

(set! *warn-on-reflection* true)

;; ToolState: the handler state above. ToolArgs: the `arguments` of a
;; `tools/call`, keyword keys. ToolSpec, ToolResult: see `spec` and `call`.

(s/def ::tool-state
  (s/keys :req-un [:mcp.config/config :mcp.code-index/code-index]
          :opt-un [:mcp.semantic-index/semantic-index]))

(s/def ::tool-args (s/keys :opt-un [::limit ::offset]))

(s/def ::tool-spec (s/keys :req-un [::name ::description ::inputSchema]))

(s/def ::tool-result (s/keys :req-un [::content]))

(defmulti spec
  "The `tools/list` entry of a tool: {:name :description :inputSchema}.

  Keyword -> ToolSpec"
  identity)

(defmulti call
  "Answers a `tools/call` of the tool with `args` (keyword keys) in
   `state`; returns {:content [...]}.

  Keyword -> ToolArgs -> ToolState -> IO ToolResult"
  (fn [tool-key _args _state] tool-key))

(def paging-properties
  "The inputSchema properties shared by every tool that obeys the Output
   budget.

  {Keyword {:type String :description String}}"
  {:limit {:type "integer" :description "Maximum number of findings (can only lower the server's Output budget)"}
   :offset {:type "integer" :description "Number of findings to skip, for paging"}})

(def namespace-properties
  "The inputSchema property `ns` shared by every tool that resolves names
   as written in a namespace.

  {Keyword {:type String :description String}}"
  {:ns {:type "string" :description "Resolve names as written in this namespace (`::k`, aliases, :refer)"}})

(defn namespace-arg
  "The `ns` tool argument of `args`, trimmed; nil when it is absent or
   blank.

  ToolArgs -> Maybe String"
  [args]
  (some-> (:ns args) str st/trim not-empty))

;; ---------------------------------------------------------------------------
;; Names as written in a namespace
;; ---------------------------------------------------------------------------

;; NameLookup: what a tool's lookup of a name gave when the agent named
;; the namespace it is written in, {:result a :unresolvable "..."}.
;; `:unresolvable` (the line `not resolvable in <ns>, all candidates:`) is
;; there only when the name does not resolve in that namespace; `:result`
;; is then the lookup of the name as it is, without the namespace.

(s/def ::result any?)

(s/def ::unresolvable string?)

(s/def ::name-lookup (s/keys :req-un [::result] :opt-un [::unresolvable]))

(defn unknown-namespace
  "The answer for an `ns-str` that names no namespace of the project, or
   nil when it is absent or names one.

  CodeIndex -> Maybe String -> Maybe String"
  [index ns-str]
  (when (and ns-str (not (code-index/namespace-name? index ns-str)))
    (str "unknown namespace: " ns-str)))

(defn lookup-in-namespace
  "Looks `name-str` up with `lookup` as written in the namespace `ns-str`
   (as it is when `ns-str` is nil). A name that does not resolve there, or
   whose lookup there is not `found?`, is looked up as it is and marked
   unresolvable.

  CodeIndex -> Maybe String -> (String -> a) -> (a -> Boolean) -> String -> NameLookup"
  [index ns-str lookup found? name-str]
  (let [in-namespace (some->> (when ns-str (code-index/qualify-in-namespace index ns-str name-str))
                              lookup)]
    (cond (nil? ns-str) {:result (lookup name-str)}
          (and (some? in-namespace) (found? in-namespace)) {:result in-namespace}
          :else {:result (lookup name-str)
                 :unresolvable (str "not resolvable in " ns-str ", all candidates:")})))

(defn under-line
  "The answer `answer-of` gives for `ctx`, under the line `line` when there
   is one, its budget lowered to leave room for that line.

  Maybe String -> AnswerContext -> (AnswerContext -> IO String) -> IO String"
  [line ctx answer-of]
  (if line
    (str line "\n" (answer-of (update-in ctx [:budget :max-chars] - (inc (count line)))))
    (answer-of ctx)))

(def freshness-note
  "The sentence of every precise tool's description that states the
   freshness guarantee (ADR-0005).

  String"
  (str "The index is re-read from changed files (by mtime) before every answer, "
       "so edits are visible immediately."))

(defn flag
  "The boolean tool argument `arg-key` of `args`, or `default` when the
   agent passed no boolean there.

  ToolArgs -> Keyword -> Boolean -> Boolean"
  [args arg-key default]
  (let [value (get args arg-key)]
    (if (boolean? value) value default)))

(defn distinct-strings
  "The distinct non-blank strings, trimmed, of a tool argument that is one
   value or a list.

  Any -> [String]"
  [value]
  (->> (if (sequential? value) value [value])
       (remove nil?)
       (map (comp st/trim str))
       (remove st/blank?)
       distinct))

(defn text-result
  "A `tools/call` result carrying one text block.

  String -> ToolResult"
  [text]
  {:content [{:type "text" :text text}]})
