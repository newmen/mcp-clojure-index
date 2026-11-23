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

(def ^:private context-width
  "The most characters of Usage context shown on a Usage line.

  Long"
  80)

(def ^:private context-lead
  "How many characters of a cut Usage context come before the Usage column.

  Long"
  20)

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
       "ns/enclosing-definition  context), where context is up to 80 characters of the source "
       "line around the Usage, whitespace collapsed (`context: false` leaves it out), then a Usage "
       "warning if one holds: `wrong arity: 3 args, takes 1|2` (`takes 2+` for varargs) on a call "
       "whose argument count matches no arity of the target; on a require of a namespace target, "
       "`unused` when nothing in the file uses the alias or a referred name, `unused refer: b, c` "
       "when only those referred names are unused (bare requires and `:refer :all` are never "
       "marked; `unused` is a hint, check macros and `resolve` before removing); at most :output/limit lines and "
       ":output/max-chars characters; `kinds`, `include_tests` and `warnings_only` (only Usages "
       "with a Usage warning) narrow it. "
       tool/freshness-note))

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
                                                     :description "Include Usages in test files (default true)"}
                                     :context {:type "boolean"
                                               :description "End each Usage line with its Usage context (default true)"}
                                     :warnings_only {:type "boolean"
                                                     :description "Only Usages that carry a Usage warning (default false)"}}
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
;;  :include-tests? true            ; false drops Usages under Test paths
;;  :context?       true            ; false leaves Usage context out
;;  :warnings-only? false}          ; true keeps only Usages with a Usage warning

(s/def ::usage-filter (s/keys :req-un [::kinds ::include-tests? ::context? ::warnings-only?]))

;; A LineReader gives the source lines of a file by its absolute path,
;; reading each file at most once per call.

(s/def ::line-reader ifn?)

(defn- usage-context
  "The Usage context of the Usage at 1-based `col` of `line`: the line with
   whitespace runs collapsed, cut to `context-width` characters around the
   column, an ellipsis marking each cut end.

  String -> Long -> String"
  [line col]
  (let [column-index (min (count line) (max 0 (dec col)))
        lead (output/collapse-whitespace (st/triml (subs line 0 column-index)))
        text (st/trimr (str lead (output/collapse-whitespace (subs line column-index))))
        length (count text)
        start (max 0 (min (- (count lead) context-lead) (- length context-width)))
        end (min length (+ start context-width))
        cut-start? (pos? start)
        cut-end? (< end length)]
    (str (when cut-start? output/ellipsis)
         (subs text (cond-> start cut-start? inc) (cond-> end cut-end? dec))
         (when cut-end? output/ellipsis))))

(defn- usage->line
  "The Compact answer line of a Usage: `path:line:col  kind  enclosing`,
   followed by its Usage context when `read-lines` is given and by its
   Usage warning, if any.

  String -> Maybe LineReader -> Usage -> IO String"
  [root read-lines {:keys [filename row col kind enclosing warning]}]
  (let [line (when read-lines (get (read-lines filename) (dec row)))]
    (cond-> (str (output/relative-path root filename) ":" row ":" col "  " kind)
      (not (st/blank? enclosing)) (str "  " enclosing)
      (not (st/blank? line)) (str "  " (usage-context line col))
      warning (str "  " warning))))

(defn- wanted?
  "True when `usage` is of one of the asked `kinds` (all when empty),
   outside the Test paths unless tests are included, and, when only
   warnings are asked for, carries a Usage warning.

  CodeIndex -> UsageFilter -> Usage -> Boolean"
  [index {:keys [kinds include-tests? warnings-only?]} {:keys [filename kind warning]}]
  (and (or (empty? kinds) (contains? kinds kind))
       (or include-tests? (not (code-index/test-file? index filename)))
       (or (not warnings-only?) (some? warning))))

(defn- target-group
  "The TargetAnswer for one target as written by the agent.

  Ambiguous candidates show no Type signature.

  AnswerContext -> Maybe LineReader -> UsageFilter -> String -> IO TargetAnswer"
  [{:keys [index] :as ctx} read-lines opts target-str]
  (let [{:keys [target candidates]} (usages/resolve-target index target-str)
        found (when (usages/has-usages? target)
                (filter (partial wanted? index opts) (usages/usages index target)))]
    (cond
      candidates {:header (output/ambiguous-header "definitions" target-str (count candidates))
                  :lines (mapv #(output/definition->line ctx % {:signature? false})
                               candidates)}
      (nil? target) {:header (str "not found: " target-str " " (output/checked index (:include-tests? opts)))}
      (= :defmethod (:target/type target))
      {:header (str (:target/name target) " is a defmethod: it is called through its multimethod, "
                    "find usages of " (code-index/qualified-name (dissoc (:definition target) :dispatch)))}
      (empty? found) {:header (str "no usages of " (:target/name target) " "
                                   (output/checked index (:include-tests? opts)))}
      :else {:header (str (:target/name target) ": " (count found)
                          (if (= 1 (count found)) " usage" " usages"))
             :lines (mapv (partial usage->line (code-index/root index) read-lines) found)})))

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
              :include-tests? (not (false? (:include_tests params)))
              :context? (not (false? (:context params)))
              :warnings-only? (true? (:warnings_only params))}
        read-lines (when (:context? opts) (memoize code-index/file-lines))
        {:keys [budget] :as ctx} (output/answer-context config params code-index)
        groups (map (partial target-group ctx read-lines opts)
                    (distinct (as-strings (:targets params))))]
    (tool/text-result
     (if (empty? groups)
       "no targets given"
       (output/render budget (findings groups (:offset budget)))))))
