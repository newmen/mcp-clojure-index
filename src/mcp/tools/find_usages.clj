(ns mcp.tools.find-usages
  "`find_usages`: every Usage of one or more targets, grouped by target, as
   Compact answer lines (ADR-0006) within the Output budget (ADR-0004)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]
            [mcp.usages :as usages]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find every Usage of one or more Clojure vars, macros, namespaces or keywords "
       "(`:k`, `:ns/k`, `::k`, `::alias/k`) in one call. "
       "Keywords are found in code and in the project's .edn files. "
       "More complete than grep: resolves aliases, `:refer`, `#'var` and `(var x)`, finds "
       "functions passed as values (`partial`, `comp`, `map` arguments), and has no false "
       "positives from other definitions with the same simple name. "
       "Marks test doubles bound in `with-redefs` (kind redef) and REPL scratch code inside "
       "`comment` (kind in-comment). "
       "Answer: grouped by target, one line per Usage (path:line:col  usage-kind  "
       "ns/enclosing-definition), at most :output/limit lines and :output/max-chars characters; "
       "`kinds` and `include_tests` narrow it."))

(defmethod tool/spec :find_usages
  [_tool-key]
  {:name "find_usages"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:targets {:type "array"
                                               :items {:type "string"}
                                               :description "Names (simple, alias-qualified or fully qualified) and/or keywords"}
                                     :kinds {:type "array"
                                             :items {:type "string" :enum (usages/kind-names)}
                                             :description "Only Usages of these kinds (default: all)"}
                                     :include_tests {:type "boolean"
                                                     :description "Include Usages in test files (default true)"}}
                                    tool/paging-properties)
                 :required ["targets"]}})

;; ---------------------------------------------------------------------------
;; Target answers
;; ---------------------------------------------------------------------------

;; A TargetAnswer is the answer for one target:
;;
;; {:header "app.core/get-uuid: 11 usages"   ; or a not-found/ambiguous line
;;  :lines  ["src/app/other.clj:6:5  call  app.other/caller" ...]}

(s/def ::target-answer (s/keys :req-un [::header] :opt-un [::lines]))

;; A UsageFilter is what the call asks for:
;;
;; {:kinds          #{"call" ...}   ; only these Usage kinds; empty for all
;;  :include-tests? true}           ; false drops Usages under Test paths

(s/def ::usage-filter (s/keys :req-un [::kinds ::include-tests?]))

(defn- usage->line
  "The Compact answer line of a Usage: `path:line:col  kind  enclosing`.

  String -> Usage -> String"
  [root {:keys [filename row col kind enclosing]}]
  (cond-> (str (output/relative-path root filename) ":" row ":" col "  " kind)
    (not (st/blank? enclosing)) (str "  " enclosing)))

(defn- wanted?
  "True when `usage` is of one of the asked `kinds` (all when empty) and,
   unless tests are included, outside the Test paths.

  CodeIndex -> UsageFilter -> Usage -> Boolean"
  [index {:keys [kinds include-tests?]} {:keys [filename kind]}]
  (and (or (empty? kinds) (contains? kinds kind))
       (or include-tests? (not (code-index/test-file? index filename)))))

(defn- target-group
  "The TargetAnswer for one target as written by the agent.

  CodeIndex -> UsageFilter -> String -> IO TargetAnswer"
  [index opts target-str]
  (let [{:keys [target candidates]} (usages/resolve-target index target-str)
        found (when (usages/has-usages? target)
                (filter (partial wanted? index opts) (usages/usages index target)))]
    (cond
      candidates {:header (output/ambiguous-header "definitions" target-str (count candidates))
                  :lines (mapv (partial output/definition->line index) candidates)}
      (nil? target) {:header (str "not found: " target-str " " (output/checked index (:include-tests? opts)))}
      (= :defmethod (:target/type target))
      {:header (str (:target/name target) " is a defmethod: it is called through its multimethod, "
                    "find usages of " (code-index/qualified-name (dissoc (:definition target) :dispatch)))}
      (empty? found) {:header (str "no usages of " (:target/name target) " "
                                   (output/checked index (:include-tests? opts)))}
      :else {:header (str (:target/name target) ": " (count found)
                          (if (= 1 (count found)) " usage" " usages"))
             :lines (mapv (partial usage->line (code-index/root index)) found)})))

(defn- findings
  "One finding per Usage line of the TargetAnswers.

  The header of an answer is joined to its first line and to the line a
  page starts at, so every page names its targets. An answer without lines
  is a finding of its own.

  [TargetAnswer] -> Long -> [String]"
  [groups offset]
  (->> groups
       (mapcat (fn [{:keys [header lines]}]
                 (if (seq lines)
                   (map-indexed (fn [i line] {:header header :first? (zero? i) :line line}) lines)
                   [{:line header}])))
       (map-indexed (fn [i {:keys [header first? line]}]
                      (if (and header (or first? (= i offset)))
                        (str header "\n" line)
                        line)))
       vec))

;; ---------------------------------------------------------------------------
;; Call
;; ---------------------------------------------------------------------------

(defn- as-strings
  "The non-blank strings of a tool argument that is one value or a list.

  Any -> [String]"
  [value]
  (->> (if (sequential? value) value [value])
       (remove nil?)
       (map (comp st/trim str))
       (remove st/blank?)))

(defmethod tool/call :find_usages
  [_tool-key params {:keys [config code-index]}]
  (let [opts {:kinds (set (as-strings (:kinds params)))
              :include-tests? (not (false? (:include_tests params)))}
        groups (map (partial target-group code-index opts) (distinct (as-strings (:targets params))))
        budget (output/budget config params)]
    (tool/text-result
     (if (empty? groups)
       "no targets given"
       (output/render budget (findings groups (:offset budget)))))))
