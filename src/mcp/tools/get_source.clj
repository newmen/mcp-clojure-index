(ns mcp.tools.get-source
  "`get_source`: the source of exactly one Definition, so an agent never
   reads a whole file to see one function (ADR-0006)."
  (:require [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Get the source of exactly one Clojure Definition (function, macro, var, protocol, record, test or namespace form). "
       "Unlike reading the file or grep, returns only that form, resolved the way find_definition resolves names "
       "(simple, alias-qualified such as `c/get-uuid`, or fully qualified); an ambiguous name returns the candidates, not several bodies. "
       "Answer: a `path:start-end` header line, then the source, at most :output/limit lines and "
       ":output/max-chars characters; a longer body ends with a truncation note, "
       "pass `offset` (in lines of the body) for the rest. "
       tool/freshness-note))

(defmethod tool/spec :get_source
  [_tool-key]
  {:name "get_source"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:name {:type "string"
                                            :description "Name: simple, alias-qualified or fully qualified"}}
                                    tool/paging-properties)
                 :required ["name"]}})

(defn- source-answer
  "Header and source lines of `definition`, paged by line within the
   Output budget.

  OutputBudget -> CodeIndex -> Definition -> IO String"
  [budget index {:keys [filename row end-row] :as definition}]
  (output/render-framed budget
                        {:header (str (output/relative-path (code-index/root index) filename)
                                      ":" row "-" end-row)}
                        (code-index/form-lines (code-index/file-lines filename) definition)))

(defmethod tool/call :get_source
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (str (:name params))
        budget (output/budget config params)]
    (tool/text-result
     (output/one-definition budget (:doc/signature config) code-index name-str
                            (code-index/resolve-name code-index name-str)
                            (partial source-answer budget code-index)))))
