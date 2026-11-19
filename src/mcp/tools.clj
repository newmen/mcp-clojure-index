(ns mcp.tools
  "Serves `tools/list` and `tools/call` from the tool registry (`mcp.tool`).
   New tools live in their own `mcp.tools.*` namespace and are required here."
  (:require [mcp.output :as output]
            [mcp.graph :as graph]
            [mcp.tool :as tool]
            [mcp.tools.find-definition]
            [mcp.tools.find-implementations]
            [mcp.tools.get-source]
            [mcp.tools.namespace-summary]
            [mcp.tools.semantic-search]))

(set! *warn-on-reflection* true)

(def ^:private legacy-tool-specs
  [{:name "find_callers"
    :description "Find all symbols that call a given symbol"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Qualified symbol name (e.g. my.ns/my-func)"}}
                  :required ["symbol"]}}
   {:name "find_callees"
    :description "Find all symbols called by a given symbol"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Qualified symbol name (e.g. my.ns/my-func)"}}
                  :required ["symbol"]}}])

(doseq [{tool-name :name :as tool-spec} legacy-tool-specs]
  (defmethod tool/spec (keyword tool-name) [_tool-key] tool-spec))

(defmethod tool/call :find_callers
  [_ params {:keys [config graph-state]}]
  (let [sym-str (or (:symbol params) "")
        sym (symbol sym-str)
        callers (graph/find-callers graph-state sym)]
    (if (empty? callers)
      {:content [{:type "text" :text (str "No callers found for '" sym-str "'.")}]}
      {:content [{:type "text" :text (output/render config params (mapv str callers))}]})))

(defmethod tool/call :find_callees
  [_ params {:keys [config graph-state]}]
  (let [sym-str (or (:symbol params) "")
        sym (symbol sym-str)
        callees (graph/find-callees graph-state sym)]
    (if (empty? callees)
      {:content [{:type "text" :text (str "No callees found for '" sym-str "'.")}]}
      {:content [{:type "text" :text (output/render config params (mapv str callees))}]})))

(defn make-initial-state
  [config]
  {:config config
   :graph-state (graph/empty-graph)})

(defn tool-definitions
  "The `tools/list` entries of all registered tools, sorted by name."
  []
  (->> (keys (methods tool/spec))
       (remove #{:default})
       (map tool/spec)
       (sort-by :name)
       vec))

(defn handle-tools-list
  "Answer `tools/list`."
  [_state]
  {:tools (tool-definitions)})

(defn handle-tool
  "Answer `tools/call` of `tool-name` with `args` in `state`."
  [tool-name args state]
  (tool/call (keyword tool-name) args state))