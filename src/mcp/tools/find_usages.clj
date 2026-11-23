(ns mcp.tools.find-usages
  "`find_usages`: every Usage of one or more targets, grouped by target, as
   Compact answer lines (ADR-0006) within the Output budget (ADR-0004)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.text :as text]
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
       "Answer: grouped by target (a header line per target, a blank line between groups), one line per Usage (path:line:col  usage-kind  "
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
;; {:header   "app.core/get-uuid: 11 usages"   ; or a not-found/ambiguous line
;;  :findings ["src/app/other.clj:6:5  call  app.other/caller" ...]}
;;
;; It is an AnswerGroup of `mcp.output` whose findings are Usage lines.

(s/def ::target-answer :mcp.output/answer-group)

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

(defn- usage->line
  "The Compact answer line of a Usage: `path:line:col  kind  enclosing`,
   followed by its Usage context (the source line around the column) when
   `read-lines` is given, and by its Usage warning, if any.

  String -> Maybe LineReader -> Usage -> IO String"
  [root read-lines {:keys [filename row col kind enclosing warning]}]
  (let [line (when read-lines (get (read-lines filename) (dec row)))]
    (cond-> (str (output/relative-path root filename) ":" row ":" col "  " kind)
      (not (st/blank? enclosing)) (str "  " enclosing)
      (not (st/blank? line)) (str "  " (text/around-column line col context-width context-lead))
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
                  :findings (mapv #(output/definition->line ctx % {:signature? false})
                               candidates)}
      (nil? target) {:header (str "not found: " target-str " " (output/checked index (:include-tests? opts)))}
      (= :defmethod (:target/type target))
      {:header (str (:target/name target) " is a defmethod: it is called through its multimethod, "
                    "find usages of " (code-index/qualified-name (dissoc (:definition target) :dispatch)))}
      (empty? found) {:header (str "no usages of " (:target/name target)
                                   (when (:warnings-only? opts) " with a Usage warning")
                                   " " (output/checked index (:include-tests? opts)))}
      :else {:header (str (:target/name target) ": " (count found)
                          (if (= 1 (count found)) " usage" " usages"))
             :findings (mapv (partial usage->line (code-index/root index) read-lines) found)})))

;; ---------------------------------------------------------------------------
;; Call
;; ---------------------------------------------------------------------------

(defmethod tool/call :find_usages
  [_tool-key params {:keys [config code-index]}]
  (let [opts {:kinds (set (tool/distinct-strings (:kinds params)))
              :include-tests? (tool/flag params :include_tests true)
              :context? (tool/flag params :context true)
              :warnings-only? (tool/flag params :warnings_only false)}
        read-lines (when (:context? opts) (memoize code-index/file-lines))
        {:keys [budget] :as ctx} (output/answer-context config params code-index)
        groups (map (partial target-group ctx read-lines opts)
                    (tool/distinct-strings (:targets params)))]
    (tool/text-result
     (if (empty? groups)
       "no targets given"
       (output/render-grouped budget groups)))))
