(ns mcp.tool
  "Tool registry. Each tool lives in its own namespace under `mcp.tools.*`
   and registers itself with one `defmethod` of `spec` and one of `call`,
   both dispatching on the tool name as a keyword (`:find_definition`).
   `mcp.tools` requires every tool namespace and serves `tools/list` and
   `tools/call` from these multimethods.

   Handler state shape (third argument of `call`):

   {:config     resolved config (see mcp.config)
    :code-index the precise index (see mcp.code-index), current at call time
    :index      legacy symbol index, used by semantic_search until ticket 11}")

(set! *warn-on-reflection* true)

(defmulti spec
  "The `tools/list` entry of a tool: {:name :description :inputSchema}."
  identity)

(defmulti call
  "Answer a `tools/call` of the tool with `args` (keyword keys) in `state`;
   returns {:content [...]}."
  (fn [tool-key _args _state] tool-key))

(def paging-properties
  "inputSchema properties shared by every tool that obeys the Output budget."
  {:limit {:type "integer" :description "Maximum number of findings (can only lower the server limit)"}
   :offset {:type "integer" :description "Number of findings to skip, for paging"}})

(defn text-result
  "A `tools/call` result carrying one text block."
  [text]
  {:content [{:type "text" :text text}]})
