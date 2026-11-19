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

(defn- summary-line
  [{:keys [row kind arglists doc private] :as definition}]
  (join-fields [(str row)
                (if private "private" "public")
                kind
                (code-index/local-name definition)
                (s/join " " arglists)
                (output/first-line doc)]))

(defn- required-by-line
  [index ns-sym]
  (str "required by: "
       (or (some->> (code-index/requiring-namespaces index ns-sym) (s/join ", "))
           "none")))

(defn- summary
  [config params index ns-def]
  (let [ns-sym (:name ns-def)
        lines (->> (code-index/definitions index)
                   (filter (comp #{ns-sym} :ns))
                   (sort-by (juxt :filename :row :col))
                   (mapv summary-line))]
    (output/render-framed config params
                          {:header (header (code-index/root index) ns-def)}
                          (conj lines (required-by-line index ns-sym)))))

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
  (->> (code-index/namespace-names index)
       (keep #(some-> (candidate-distance asked-str %) (vector %)))
       sort
       (take max-candidates)
       (map second)))

(defn- unknown-namespace-answer
  "`output/not-found` for the namespace `asked-str`, followed by the
   close namespace names when there are any."
  [index asked-str]
  (let [close (candidates index asked-str)]
    (cond-> (output/not-found index (str "namespace " asked-str))
      (seq close) (str "; close: " (s/join ", " close)))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :namespace_summary
  [_tool-key params {:keys [config code-index]}]
  (let [asked-str (s/trim (str (:ns params)))]
    (tool/text-result
     (if-let [ns-def (code-index/namespace-definition code-index asked-str)]
       (summary config params code-index ns-def)
       (unknown-namespace-answer code-index asked-str)))))
