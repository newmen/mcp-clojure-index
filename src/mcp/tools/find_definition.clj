(ns mcp.tools.find-definition
  "`find_definition`: where a name is defined, resolved like the compiler
   resolves it (ADR-0002), as Compact answer lines (ADR-0006)."
  (:require [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find where a Clojure var, macro, protocol, record, test (deftest), multimethod or "
       "one of its methods, spec (s/def), form of a project macro (lint-as) or namespace is defined. "
       "Unlike grep, resolves the name the way the code does: a simple name, an alias-qualified "
       "name as written in some namespace (e.g. `c/get-uuid`) or a fully qualified one; "
       "a defmethod as name plus dispatch value (`area :circle`); a spec by keyword "
       "(`:app.core/uuid`, `::uuid`, `::c/uuid`); "
       "an ambiguous name returns the candidates. "
       "Answer: one line per Definition (path:line  kind  ns/name  [arglists]  first docstring line), "
       "at most :output/limit lines and :output/max-chars characters. Use get_source for the body."))

(defmethod tool/spec :find_definition
  [_tool-key]
  {:name "find_definition"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:name {:type "string"
                                            :description "Name: simple, alias-qualified or fully qualified; `multi dispatch-value` for a defmethod; a keyword for a spec"}}
                                    tool/paging-properties)
                 :required ["name"]}})

(defmethod tool/call :find_definition
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (str (:name params))
        budget (output/budget config params)]
    (tool/text-result
     (output/one-definition budget code-index name-str
                            (code-index/resolve-name code-index name-str)
                            #(output/render budget [(output/definition->line code-index %)])))))
