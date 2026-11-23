(ns mcp.tools.find-definition
  "`find_definition`: where a name is defined, resolved like the compiler
   resolves it (ADR-0002), as Compact answer lines (ADR-0006)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.text :as text]
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

(defn- full-doc-answer
  "The `doc: \"full\"` answer for `name-str` that resolved to
   `definitions`, one finding per Definition.

  A finding that alone exceeds the Output budget has its docstring cut and
  ends with the docstring Truncation note.

  AnswerContext -> String -> [Definition] -> String"
  [{:keys [budget index] :as ctx} name-str definitions]
  (let [findings (mapv (partial full-doc-finding index) definitions)
        answer (fn [header]
                 (output/render-framed budget {:header header} findings
                                       {:text-of full-doc-text :cut cut-full-doc}))]
    (output/one-definition ctx name-str definitions
                           (fn [_definition] (answer nil))
                           (fn [candidates]
                             (answer (output/ambiguous-header "definitions" name-str (count candidates)))))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :find_definition
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (str (:name params))
        ctx (output/answer-context config params code-index)
        definitions (code-index/resolve-name code-index name-str)]
    (tool/text-result
     (if (= "full" (:doc params))
       (full-doc-answer ctx name-str definitions)
       (output/one-definition ctx name-str definitions
                              #(output/render (:budget ctx) [(output/definition->line ctx %)]))))))
