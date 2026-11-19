(ns mcp.tools.find-definition
  "`find_definition`: where a name is defined, resolved like the compiler
   resolves it (ADR-0002), as Compact answer lines (ADR-0006)."
  (:require [clojure.string :as s]
            [mcp.code-index :as code-index]
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

(defn definition->line
  "Compact answer line of a Definition:
   `path:line  kind  ns/name  [arglists]  first docstring line`."
  [root {:keys [filename row kind arglists doc] :as definition}]
  (->> [(str (output/relative-path root filename) ":" row)
        kind
        (code-index/qualified-name definition)
        (s/join " " arglists)
        (output/first-line doc)]
       (remove s/blank?)
       (s/join "  ")))

(defn not-found
  "Answer for a name that resolves to no Definition, saying what was searched."
  [index name-str]
  (str "not found: " name-str
       " (checked " (count (code-index/files index)) " files"
       (when (some (partial code-index/test-file? index) (code-index/files index))
         ", including tests")
       ")"))

(defn ambiguous
  "Answer for a name that resolves to several Definitions: a header and the
   candidate `lines` within the Output budget."
  [config params name-str lines]
  (let [header (str "ambiguous: " (count lines) " definitions named " name-str
                    ", pass a qualified name")
        budget (update config :output/max-chars - (inc (count header)))]
    (str header "\n" (output/render budget params lines))))

(defmethod tool/call :find_definition
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (s/trim (str (:name params)))
        lines (mapv (partial definition->line (:root code-index))
                    (code-index/resolve-name code-index name-str))]
    (tool/text-result
     (case (count lines)
       0 (not-found code-index name-str)
       1 (output/render config params lines)
       (ambiguous config params name-str lines)))))
