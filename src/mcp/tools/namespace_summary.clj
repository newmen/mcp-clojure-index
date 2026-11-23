(ns mcp.tools.namespace-summary
  "`namespace_summary`: what a namespace contains, without reading the file,
   as Compact answer lines (ADR-0006): one line per Definition, then the
   namespaces that require it. Each line carries the Definition's Type
   signature, the one thing grep cannot give in a line; Docstring summaries
   only on request (`detail`), so the default answer stays small (ADR-0006)."
  (:require [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.docstring :as docstring]
            [mcp.output :as output]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Summarise a Clojure namespace without reading its file. "
       "Unlike grep, lists every Definition (public and private, including tests, protocols, "
       "records and specs) with its arglists, and which namespaces require it. "
       "Answer: a header line (ns name  path  Docstring summary), then one line per Definition "
       "(line  kind  name  [arglists]  Type signature, the signature when the project's "
       "docstrings state one; a private Definition whose kind is not `defn-` is named "
       "`^:private name`; every spec written in the namespace's files is listed, "
       "a spec of the namespace's own keyword as `::name`, of another keyword in full; "
       "`detail: true` adds  Docstring summary), "
       "then `required by: ...`; "
       "no bodies, at most :output/limit lines and :output/max-chars characters. "
       "Use get_source for a body. "
       tool/freshness-note))

(def ^:private max-candidates 5)

(def ^:private max-candidate-distance 3)

(defmethod tool/spec :namespace_summary
  [_tool-key]
  {:name "namespace_summary"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:ns {:type "string"
                                          :description "Fully qualified namespace name, e.g. my.app.core"}
                                     :detail {:type "boolean"
                                              :description "Add each Definition's Docstring summary after its Type signature; Type signatures are shown without it (default false)"}}
                                    tool/paging-properties)
                 :required ["ns"]}})

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn- join-fields
  "Joins the non-blank `fields` of an answer line with two spaces.

  [Maybe String] -> String"
  [fields]
  (->> fields (remove st/blank?) (st/join "  ")))

(defn- header
  "The header line of the namespace `ns-def`, a clj-kondo namespace
   definition entry: `ns name  path  Docstring summary`.

  String -> AnalysisEntry -> String"
  [root {:keys [filename doc] ns-name :name}]
  (join-fields [(str "ns " ns-name)
                (output/relative-path root filename)
                (docstring/docstring-summary doc)]))

(def ^:private self-private-kinds
  "The kinds whose defining form says by itself that it is private.

  #{String}"
  #{"defn-"})

(defn- name-as-written
  "The name of a Definition of the namespace as its source writes it:
   `code-index/local-name`, preceded by `^:private` when the Definition is
   private and its kind is not `defn-`, which says so by itself.

  Definition -> String"
  [{:keys [kind private] :as definition}]
  (cond->> (code-index/local-name definition)
    (and private (not (self-private-kinds kind))) (str "^:private ")))

(defn- summary-line
  "The answer line of one Definition of the namespace with its Type
   signature, found by `signature-strategy`; with `:detail?`, also its
   Docstring summary.

  SignatureStrategy -> {:detail? Boolean} -> Definition -> String"
  [signature-strategy {:keys [detail?]} {:keys [row kind arglists] :as definition}]
  (let [{:keys [summary signature]} (docstring/definition-doc signature-strategy definition)]
    (join-fields [(str row)
                  kind
                  (name-as-written definition)
                  (st/join " " arglists)
                  signature
                  (when detail? summary)])))

(defn- required-by-line
  "The closing line naming the namespaces that require `ns-sym`.

  CodeIndex -> Symbol -> String"
  [index ns-sym]
  (str "required by: "
       (or (some->> (code-index/requiring-namespaces index ns-sym) (st/join ", "))
           "none")))

(defn- summary
  "The answer for the namespace `ns-def`: header, Definitions, requirers;
   the Definition lines carry Type signatures, and with `:detail?` also
   Docstring summaries.

  AnswerContext -> {:detail? Boolean} -> AnalysisEntry -> String"
  [{:keys [budget index signature-strategy]} opts ns-def]
  (let [ns-sym (:name ns-def)
        lines (->> (code-index/definitions index)
                   (filter (comp #{ns-sym} :ns))
                   (sort-by (juxt :filename :row :col (comp str :name)))
                   (mapv (partial summary-line signature-strategy opts)))]
    (output/render-framed budget
                          {:header (header (code-index/root index) ns-def)}
                          (conj lines (required-by-line index ns-sym)))))

;; ---------------------------------------------------------------------------
;; Unknown namespace
;; ---------------------------------------------------------------------------

(defn- edit-distance
  "The Levenshtein distance between strings `a` and `b`.

  String -> String -> Long"
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
   it is not a close candidate.

  String -> String -> Maybe Long"
  [asked-str ns-str]
  (let [distance (edit-distance asked-str ns-str)]
    (cond (st/includes? ns-str asked-str) 0
          (<= distance max-candidate-distance) distance)))

(defn- candidates
  "The namespace names closest to `asked-str`, closest first.

  CodeIndex -> String -> [String]"
  [index asked-str]
  (->> (code-index/namespace-names index)
       (keep #(some-> (candidate-distance asked-str %) (vector %)))
       sort
       (take max-candidates)
       (map second)))

(defn- unknown-namespace-answer
  "`output/not-found` for the namespace `asked-str`, followed by the
   close namespace names when there are any.

  CodeIndex -> String -> String"
  [index asked-str]
  (let [close (candidates index asked-str)]
    (cond-> (output/not-found index (str "namespace " asked-str))
      (seq close) (str "; close: " (st/join ", " close)))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :namespace_summary
  [_tool-key params {:keys [config code-index]}]
  (let [asked-str (st/trim (str (:ns params)))]
    (tool/text-result
     (if-let [ns-def (code-index/namespace-definition code-index asked-str)]
       (summary (output/answer-context config params code-index)
                {:detail? (tool/flag params :detail false)}
                ns-def)
       (unknown-namespace-answer code-index asked-str)))))
