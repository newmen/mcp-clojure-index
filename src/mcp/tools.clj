(ns mcp.tools
  "Loads every tool of the registry (`mcp.tool`) and serves `tools/list`;
   `tools/call` goes straight to `mcp.tool/call`. New tools live in their
   own `mcp.tools.*` namespace and are required here."
  (:require [mcp.tool :as tool]
            [mcp.tools.find-definition]
            [mcp.tools.find-implementations]
            [mcp.tools.find-usages]
            [mcp.tools.get-source]
            [mcp.tools.namespace-summary]
            [mcp.tools.semantic-search]))

(set! *warn-on-reflection* true)

(defn tool-definitions
  "The `tools/list` entries of all registered tools, sorted by name."
  []
  (->> (keys (methods tool/spec))
       (remove #{:default})
       (map tool/spec)
       (sort-by :name)
       vec))
