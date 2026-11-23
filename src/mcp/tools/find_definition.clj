(ns mcp.tools.find-definition
  "`find_definition`: where a name is defined, resolved like the compiler
   resolves it (ADR-0002), as Compact answer lines (ADR-0006)."
  (:require [clojure.string :as st]
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
       "Answer: one line per Definition (path:line  kind  ns/name  [arglists]  Type signature  "
       "Docstring summary), "
       "at most :output/limit lines and :output/max-chars characters. "
       "With doc: \"full\", each Definition (path:line  kind  ns/name  [arglists]) is followed by "
       "its whole docstring, indented by two spaces; limit and offset count Definitions, and a "
       "docstring longer than :output/max-chars ends with `truncated: docstring shown A of B chars`. "
       "Use get_source for the body. "
       tool/freshness-note))

(defmethod tool/spec :find_definition
  [_tool-key]
  {:name "find_definition"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:name {:type "string"
                                            :description "Name: simple, alias-qualified or fully qualified; `multi dispatch-value` for a defmethod; a keyword for a spec"}
                                     :doc {:type "string"
                                           :enum ["summary" "full"]
                                           :description "\"summary\" (default): the Docstring summary on the Definition line; \"full\": the whole docstring under it"}}
                                    tool/paging-properties)
                 :required ["name"]}})

;; ---------------------------------------------------------------------------
;; Full docstrings
;; ---------------------------------------------------------------------------

(defn- indentation
  "The number of leading whitespace characters of `line`.

  String -> Long"
  [line]
  (- (count line) (count (st/triml line))))

(defn- docstring-text
  "The docstring `doc` without the source indentation its continuation
   lines share, and without trailing whitespace; nil when it is blank.

  Maybe String -> Maybe String"
  [doc]
  (when-not (st/blank? doc)
    (let [[first-line & more] (st/split doc #"\n" -1)
          shared (->> more (remove st/blank?) (map indentation) (reduce min Long/MAX_VALUE))]
      (->> more
           (map #(if (st/blank? %) "" (subs % shared)))
           (cons (st/triml first-line))
           (st/join "\n")
           st/trimr))))

(defn- full-doc-finding
  "A Definition's `head` line, then the docstring `text` indented by two
   spaces with its line breaks, or `(no docstring)` when `text` is nil.

  String -> Maybe String -> String"
  [head text]
  (->> (if text (st/split text #"\n" -1) ["(no docstring)"])
       (map #(if (st/blank? %) "" (str "  " %)))
       (cons head)
       (st/join "\n")))

(defn- full-doc-answer
  "The `doc: \"full\"` answer for `name-str` that resolved to
   `definitions`, one finding per Definition.

  A finding that alone exceeds the Output budget has its docstring cut and
  ends with the docstring Truncation note.

  OutputBudget -> CodeIndex -> String -> [Definition] -> String"
  [budget index name-str definitions]
  (let [heads (map (partial output/definition-head index) definitions)
        texts (map (comp docstring-text :doc) definitions)
        findings (mapv full-doc-finding heads texts)
        parts-of (zipmap findings (map vector heads texts))
        options {:cut (fn [room finding]
                        (let [[head text] (parts-of finding)]
                          (if text
                            (output/cut-finding "docstring" (partial full-doc-finding head) text room)
                            (subs finding 0 (min room (count finding))))))}]
    (case (count definitions)
      0 (output/not-found index name-str)
      1 (output/render budget findings options)
      (output/render-framed budget
                            {:header (output/ambiguous-header "definitions" name-str (count definitions))}
                            findings
                            options))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :find_definition
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (str (:name params))
        budget (output/budget config params)
        definitions (code-index/resolve-name code-index name-str)]
    (tool/text-result
     (if (= "full" (:doc params))
       (full-doc-answer budget code-index name-str definitions)
       (output/one-definition budget (:doc/signature config) code-index name-str definitions
                              #(output/render budget [(output/definition->line (:doc/signature config)
                                                        code-index %)]))))))
