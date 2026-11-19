(ns mcp.tools.namespace-summary
  "`namespace_summary`: what a namespace contains, without reading the file,
   as Compact answer lines (ADR-0006): one line per Definition, then the
   namespaces that require it."
  (:require [clojure.string :as s]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Summarise a Clojure namespace without reading its file. "
       "Unlike grep, lists every Definition (public and private, including tests, protocols "
       "and records) with its arglists and first docstring line, and which namespaces require it. "
       "Answer: a header line (ns name  path  first docstring line), then one line per Definition "
       "(line  public|private  kind  name  [arglists]  first docstring line), then `required by: ...`; "
       "no bodies, at most :output/limit lines and :output/max-chars characters. "
       "Use get_source for a body."))

(def ^:private max-candidates 5)

(def ^:private max-candidate-distance 3)

(defmethod tool/spec :namespace_summary
  [_tool-key]
  {:name "namespace_summary"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:ns {:type "string"
                                          :description "Fully qualified namespace name, e.g. my.app.core"}}
                                    tool/paging-properties)
                 :required ["ns"]}})

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn- join-fields
  [fields]
  (->> fields (remove s/blank?) (s/join "  ")))

(defn- header
  [root {:keys [filename doc] ns-name :name}]
  (join-fields [(str "ns " ns-name)
                (output/relative-path root filename)
                (output/first-line doc)]))

(defn- definition->line
  [{:keys [row kind arglists doc private] def-name :name}]
  (join-fields [(str row)
                (if private "private" "public")
                kind
                (str def-name)
                (s/join " " arglists)
                (output/first-line doc)]))

(defn- required-by-line
  [index ns-sym]
  (->> (code-index/entries index :namespace-usages)
       (filter (comp #{ns-sym} :to))
       (map :from)
       (remove #{ns-sym})
       (into (sorted-set-by #(compare (str %1) (str %2))))
       (s/join ", ")
       (#(str "required by: " (if (s/blank? %) "none" %)))))

(defn- summary
  [config params index ns-def]
  (let [ns-sym (:name ns-def)
        head (header (:root index) ns-def)
        lines (->> (code-index/definitions index)
                   (filter (comp #{ns-sym} :ns))
                   (sort-by (juxt :filename :row :col))
                   (mapv definition->line))
        budget (update config :output/max-chars - (inc (count head)))]
    (str head "\n" (output/render budget params (conj lines (required-by-line index ns-sym))))))

;; ---------------------------------------------------------------------------
;; Unknown namespace
;; ---------------------------------------------------------------------------

(defn- edit-distance
  "Levenshtein distance between strings `a` and `b`."
  [^String a ^String b]
  (peek
   (reduce (fn [previous-row a-char]
             (reduce (fn [row [b-index b-char]]
                       (conj row (min (inc (peek row))
                                      (inc (nth previous-row (inc b-index)))
                                      (+ (nth previous-row b-index)
                                         (if (= a-char b-char) 0 1)))))
                     [(inc (first previous-row))]
                     (map-indexed vector b)))
           (vec (range (inc (count b))))
           a)))

(defn- candidate-distance
  "How far namespace name `ns-str` is from the asked `asked-str`; nil when
   it is not a close candidate."
  [asked-str ns-str]
  (let [distance (edit-distance asked-str ns-str)]
    (cond (s/includes? ns-str asked-str) 0
          (<= distance max-candidate-distance) distance)))

(defn- candidates
  [index asked-str]
  (->> (code-index/entries index :namespace-definitions)
       (map (comp str :name))
       distinct
       (keep #(some-> (candidate-distance asked-str %) (vector %)))
       sort
       (take max-candidates)
       (map second)))

(defn- not-found
  [index asked-str]
  (let [files (code-index/files index)
        close (candidates index asked-str)]
    (str "not found: namespace " asked-str
         (if (seq close)
           (str "; close: " (s/join ", " close))
           (str " (checked " (count files) " files"
                (when (some (partial code-index/test-file? index) files)
                  ", including tests")
                ")")))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :namespace_summary
  [_tool-key params {:keys [config code-index]}]
  (let [asked-str (s/trim (str (:ns params)))
        ns-def (->> (code-index/entries code-index :namespace-definitions)
                    (filter (comp #{asked-str} str :name))
                    (sort-by (juxt :filename :row))
                    first)]
    (tool/text-result
     (if ns-def
       (summary config params code-index ns-def)
       (not-found code-index asked-str)))))
