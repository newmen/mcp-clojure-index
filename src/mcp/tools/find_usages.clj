(ns mcp.tools.find-usages
  "`find_usages`: every Usage of one or more targets, grouped by target, as
   Compact answer lines (ADR-0006) within the Output budget (ADR-0004)."
  (:require [clojure.string :as s]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]
            [mcp.usages :as usages]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find every Usage of one or more Clojure vars, macros or namespaces in one call. "
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
                                               :description "Names: simple, alias-qualified or fully qualified"}
                                     :kinds {:type "array"
                                             :items {:type "string" :enum (usages/kind-names)}
                                             :description "Only Usages of these kinds (default: all)"}
                                     :include_tests {:type "boolean"
                                                     :description "Include Usages in test files (default true)"}}
                                    tool/paging-properties)
                 :required ["targets"]}})

;; ---------------------------------------------------------------------------
;; Groups
;; ---------------------------------------------------------------------------

;; A group is the answer for one target:
;;
;; {:header "app.core/get-uuid: 11 usages"   ; or a not-found/ambiguous line
;;  :lines  ["src/app/other.clj:6:5  call  app.other/caller" ...]}

(defn- searched
  [index include-tests?]
  (let [checked (cond->> (code-index/files index)
                  (not include-tests?) (remove (partial code-index/test-file? index)))]
    (str "(checked " (count checked) " files, "
         (if include-tests? "including" "excluding") " tests)")))

(defn- usage->line
  [root {:keys [filename row col kind enclosing]}]
  (str (output/relative-path root filename) ":" row ":" col "  " kind "  " enclosing))

(defn- wanted?
  [index {:keys [kinds include-tests?]} {:keys [filename kind]}]
  (and (or (empty? kinds) (contains? kinds kind))
       (or include-tests? (not (code-index/test-file? index filename)))))

(defn- target-group
  [index opts target-str]
  (let [{:keys [target candidates]} (usages/resolve-target index target-str)
        found (when target
                (filter (partial wanted? index opts) (usages/usages index target)))]
    (cond
      candidates {:header (str "ambiguous: " target-str " is any of "
                               (s/join ", " (sort (map code-index/qualified-name candidates)))
                               ", pass a qualified name")}
      (nil? target) {:header (str "not found: " target-str " " (searched index (:include-tests? opts)))}
      (empty? found) {:header (str "no usages of " (:target/name target) " "
                                   (searched index (:include-tests? opts)))}
      :else {:header (str (:target/name target) ": " (count found)
                          (if (= 1 (count found)) " usage" " usages"))
             :lines (mapv (partial usage->line (:root index)) found)})))

(defn- findings
  "One finding per Usage line; a group's header is joined to its first line
   and to the line a page starts at, so every page names its targets. A
   group without lines is a finding of its own."
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
  [value]
  (->> (if (sequential? value) value [value])
       (remove nil?)
       (map (comp s/trim str))
       (remove s/blank?)))

(defmethod tool/call :find_usages
  [_tool-key params {:keys [config code-index]}]
  (let [opts {:kinds (set (as-strings (:kinds params)))
              :include-tests? (not (false? (:include_tests params)))}
        groups (map (partial target-group code-index opts) (distinct (as-strings (:targets params))))]
    (tool/text-result
     (if (empty? groups)
       "no targets given"
       (output/render config params (findings groups (max 0 (or (:offset params) 0))))))))
