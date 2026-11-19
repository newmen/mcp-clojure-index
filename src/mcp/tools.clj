(ns mcp.tools
  "Serves `tools/list` and `tools/call` from the tool registry (`mcp.tool`).
   New tools live in their own `mcp.tools.*` namespace and are required here."
  (:require [mcp.tool :as tool]
            [mcp.tools.find-definition]
            [mcp.tools.find-implementations]
            [mcp.tools.find-usages]
            [mcp.tools.get-source]
            [mcp.tools.namespace-summary]
            [mcp.tools.semantic-search]))

(set! *warn-on-reflection* true)

(defn make-initial-state
  "Handler state before the code index and the semantic index are attached."
  [config]
  {:config config})

(defn tool-definitions
  "The `tools/list` entries of all registered tools, sorted by name."
  []
  (->> (keys (methods tool/spec))
       (remove #{:default})
       (map tool/spec)
       (sort-by :name)
       vec))

(defn handle-tool
  "Answer `tools/call` of `tool-name` with `args` in `state`."
  [tool-name args state]
  (tool/call (keyword tool-name) args state))
