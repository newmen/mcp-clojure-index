(ns mcp.tools.find-definition
  "`find_definition`: where names are defined, each resolved like the
   compiler resolves it (ADR-0002), as Compact answer lines (ADR-0006)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.text :as text]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find where Clojure vars, macros, protocols, records, tests (deftest), multimethods or "
       "their methods, specs (s/def), forms of a project macro (lint-as) or namespaces are "
       "defined, several names in one call. "
       "Unlike grep, resolves each name the way the code does: a simple name, an alias-qualified "
       "name as written in some namespace (e.g. `c/get-uuid`) or a fully qualified one; "
       "a defmethod as name plus dispatch value (`area :circle`); a spec by keyword "
       "(`:app.core/uuid`, `::uuid`, `::c/uuid`); "
       "an ambiguous name returns the candidates. "
       "Answer: one group per distinct name, laid out like `rg --heading`: a header line with "
       "the name as asked (or `name: not found (checked N files, ...)`, or the ambiguity), then "
       "one line per Definition (path:line  kind  ns/name  [arglists]  Type signature  "
       "Docstring summary), a blank line between groups; "
       "at most :output/limit Definitions and :output/max-chars characters, limit and offset "
       "count Definitions across groups. "
       "With doc: \"full\", each Definition (path:line  kind  ns/name  [arglists]) is followed by "
       "its whole docstring, indented by two spaces; a docstring longer than :output/max-chars "
       "ends with `truncated: docstring shown A of B chars`. "
       "Use get_source for the body. "
       tool/freshness-note))

(defmethod tool/spec :find_definition
  [_tool-key]
  {:name "find_definition"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:names {:type "array"
                                             :items {:type "string"}
                                             :description "Names, each simple, alias-qualified or fully qualified; `multi dispatch-value` for a defmethod; a keyword for a spec"}
                                     :doc {:type "string"
                                           :enum ["summary" "full"]
                                           :description "\"summary\" (default): the Docstring summary on the Definition line; \"full\": the whole docstring under it"}}
                                    tool/namespace-properties
                                    tool/paging-properties)
                 :required ["names"]}})

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

;; FullDocFinding: one finding of a `doc: "full"` answer, a Definition's
;; `head` line and its docstring `text` (nil when there is none),
;; {:head "src/app/core.clj:3  defn  app.core/f  [x]" :text "Docstring."}.

(s/def ::head string?)

(s/def ::text (s/nilable string?))

(s/def ::full-doc-finding (s/keys :req-un [::head ::text]))

(defn- full-doc-finding
  "The FullDocFinding of `definition`.

  CodeIndex -> Definition -> FullDocFinding"
  [index definition]
  {:head (output/definition-head index definition)
   :text (docstring-text (:doc definition))})

(defn- full-doc-text
  "The text of a FullDocFinding: its head line, then its docstring
   indented by two spaces with its line breaks, or `(no docstring)`.

  FullDocFinding -> String"
  [{:keys [head text]}]
  (->> (if text (st/split text #"\n" -1) ["(no docstring)"])
       (map #(if (st/blank? %) "" (str "  " %)))
       (cons head)
       (st/join "\n")))

(defn- cut-full-doc
  "A FullDocFinding that alone exceeds the Output budget, cut to `room`
   characters: its docstring cut, ending with the docstring Truncation
   note.

  Long -> FullDocFinding -> String"
  [room {:keys [text] :as finding}]
  (if text
    (output/cut-finding {:what :docstring
                         :text text
                         :finding-of #(full-doc-text (assoc finding :text %))}
                        room)
    (text/ellipsis-cut room (full-doc-text finding))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defn- name-group
  "The AnswerGroup of `name-str` as the agent wrote it, in the namespace
   `ns-str` when one is given: headed by the name, its Definitions as
   findings; a name that is not found or is ambiguous says so in the
   header, one that does not resolve in `ns-str` heads it with the
   `not resolvable` line.

  CodeIndex -> Maybe String -> String -> AnswerGroup"
  [index ns-str name-str]
  (let [{definitions :result :keys [unresolvable]}
        (tool/lookup-in-namespace index ns-str (partial code-index/resolve-name index) seq name-str)
        group (case (count definitions)
                0 {:header (str name-str ": not found " (output/checked index))}
                1 {:header name-str :findings definitions}
                {:header (output/ambiguous-header "definitions" name-str (count definitions))
                 :findings definitions})]
    (cond-> group
      unresolvable (update :header (partial str unresolvable "\n")))))

(defn- answer
  "The answer of `find_definition` for the AnswerGroups `groups`.

  AnswerContext -> ToolArgs -> [AnswerGroup] -> String"
  [{:keys [budget index] :as ctx} params groups]
  (cond
    (empty? groups) "no names given"
    (= "full" (:doc params))
    (output/render-grouped budget
                           (map #(update % :findings (partial map (partial full-doc-finding index)))
                                groups)
                           {:text-of full-doc-text
                            :cut cut-full-doc})
    :else (output/render-grouped budget groups
                                 {:text-of (partial output/definition->line ctx)})))

(defmethod tool/call :find_definition
  [_tool-key params {:keys [config code-index]}]
  (let [ns-str (tool/namespace-arg params)]
    (tool/text-result
     (or (tool/unknown-namespace code-index ns-str)
         (answer (output/answer-context config params code-index)
                 params
                 (map (partial name-group code-index ns-str) (tool/distinct-strings (:names params))))))))
