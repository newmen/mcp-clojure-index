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
            [clojure.string :as st]))

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
