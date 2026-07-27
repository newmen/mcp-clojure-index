(ns mcp.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:import (java.security MessageDigest)
           (java.util UUID)
           (java.math BigInteger)))

(set! *warn-on-reflection* true)

(defn- get-top-children
  [^String source-str]
  (let [zloc (z/of-string* source-str {:track-position? true})
        node (z/node zloc)]
    (n/children node)))

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn sha-256
  [^String encoding-str]
  (let [^MessageDigest digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes ^String encoding-str "UTF-8"))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

;; ---------------------------------------------------------------------------
;; File detection
;; ---------------------------------------------------------------------------

(defn clojure-file?
  [path]
  (let [path-str (if (instance? java.io.File path)
                   (.getPath ^java.io.File path)
                   path)
        lc (s/lower-case ^String path-str)]
    (or (s/ends-with? lc ".clj")
        (s/ends-with? lc ".cljc")
        (s/ends-with? lc ".cljs"))))

(defn edn-file?
  [path]
  (let [path-str (if (instance? java.io.File path)
                   (.getPath ^java.io.File path)
                   path)]
    (s/ends-with? (s/lower-case ^String path-str) ".edn")))

;; ---------------------------------------------------------------------------
;; Top-level form matching
;; ---------------------------------------------------------------------------

(defn- classify-form-type
  [^String form-str]
  (let [trimmed (s/triml form-str)]
    (cond
      (s/starts-with? trimmed "(defn ")       :fn
      (s/starts-with? trimmed "(defn-")       :fn
      (s/starts-with? trimmed "(defmacro ")   :macro
      (s/starts-with? trimmed "(defprotocol ") :protocol
      (s/starts-with? trimmed "(defrecord ")  :record
      (s/starts-with? trimmed "(deftype ")    :record
      (s/starts-with? trimmed "(def ")        :val
      (s/starts-with? trimmed "(defonce ")    :val
      (s/starts-with? trimmed "(ns ")         :ns
      :else nil)))

(defn- form-visibility
  [^String form-str]
  (if (s/starts-with? (s/triml form-str) "(defn-")
    :private
    :public))

(defn- strip-trailing-delims
  [^String form-str]
  (let [s (s/replace form-str #"[\)\]\}]" "")]
    (s/replace s #"^[\(\[\{]" "")))

(defn- extract-form-name
  [^String form-str]
  (try
    (let [trimmed (s/triml form-str)
          after-paren (subs trimmed 1)
          after-open (s/triml after-paren)
          parts (s/split after-open #"\s+" 3)]
      (when (>= (count parts) 2)
        (let [second ^String (second parts)]
          (when-not (s/blank? second)
            (if (s/starts-with? second "^")
              ;; metadata prefix — find the name by skipping meta patterns
              (let [rest (nth parts 2 "")
                    rest-parts (s/split rest #"\s+")]
                (when (seq rest-parts)
                  (let [name-candidate (first rest-parts)]
                    (when-not (s/blank? name-candidate)
                      (strip-trailing-delims name-candidate)))))
              (strip-trailing-delims second))))))
    (catch Exception _ nil)))

(defn- line-count
  [^String form-str]
  (if (zero? (count form-str))
    0
    (loop [i 0
           cnt 1]
      (let [idx (.indexOf form-str (int \newline) i)]
        (if (neg? idx)
          cnt
          (recur (inc idx) (inc cnt)))))))

;; ---------------------------------------------------------------------------
;; CST-based top-level form extraction via rewrite-clj
;; ---------------------------------------------------------------------------

(def ^:private top-level-form-tags #{:list :map :vector :set})

(defn- form-tag?
  [tag]
  (contains? top-level-form-tags tag))

(defn- container-tag?
  [tag]
  (contains? top-level-form-tags tag))

(defn- top-level-forms-cst
  [^String source-str]
  (try
    (loop [remaining (get-top-children source-str)
           line (int 1)
           forms []]
      (if-let [child (first remaining)]
        (let [tag (n/tag child)
              cstr (n/string child)
              lines-in-str (line-count cstr)
              next-line (int (+ line (dec lines-in-str)))]
          (if (form-tag? tag)
            (recur (rest remaining)
                   next-line
                   (conj forms {:string cstr
                                :start-line line
                                :cst-node child}))
            (recur (rest remaining)
                   next-line
                   forms)))
        forms))
    (catch Exception _ nil)))

(defn- compute-end-line
  [^String form-str start-line]
  (let [nl (if (s/blank? form-str) 1 (line-count form-str))]
    (+ start-line (dec nl))))

;; ---------------------------------------------------------------------------
;; Metadata extraction (docstring, arglists, tag)
;; ---------------------------------------------------------------------------

(defn- node-sexpr-safe
  [node]
  (try (n/sexpr node) (catch Exception _ nil)))

(defn- extract-docstring
  [children-strs]
  (when (and (>= (count children-strs) 1)
             (string? (first children-strs)))
    (first children-strs)))

(defn- extract-arglists
  [children-nodes ftype]
  (when (and (= :fn ftype)
             (>= (count children-nodes) 2))
    (let [body (drop 2 children-nodes)
          first-vector (some #(when (= :vector (n/tag %)) %) body)]
      (when first-vector
        (node-sexpr-safe first-vector)))))

(defn- extract-tag
  [children-nodes]
  (some (fn [child]
          (when (= :meta (n/tag child))
            (try
              (let [meta-children (n/children child)
                    meta-val (first meta-children)
                    meta-sexpr (node-sexpr-safe meta-val)]
                (when (map? meta-sexpr)
                  (:tag meta-sexpr)))
              (catch Exception _ nil))))
        children-nodes))

(defn- extract-form-metadata-from-node
  [cst-node ftype]
  (try
    (let [children (n/children cst-node)
          meaningful (remove (comp #{:whitespace :newline} n/tag)
                             children)
          body-start (drop 2 meaningful)
          body-strings (keep node-sexpr-safe body-start)
          doc (when (and ftype
                         (not= :ns ftype)
                         (not= :protocol ftype))
                (extract-docstring body-strings))
          arglists (extract-arglists meaningful ftype)
          tag (extract-tag meaningful)]
      (cond-> {}
        doc (assoc :doc doc)
        arglists (assoc :arglists arglists)
        tag (assoc :tag tag)))
    (catch Exception _ {})))

;; ---------------------------------------------------------------------------
;; Referenced symbol extraction from body
;; ---------------------------------------------------------------------------

(defn- walk-container-nodes
  "Walk only container nodes (list/vector/map/set) to collect symbol tokens.
  Avoids calling n/children on leaf nodes like :token, :keyword which throw."
  [nodes]
  (lazy-seq
    (when-let [node (first nodes)]
      (let [tag (n/tag node)
            result (when (= :token tag)
                     (let [sexpr (node-sexpr-safe node)]
                       (when (instance? clojure.lang.Symbol sexpr)
                         [sexpr])))]
        (if (container-tag? tag)
          (concat result (walk-container-nodes (n/children node))
                  (walk-container-nodes (rest nodes)))
          (concat result (walk-container-nodes (rest nodes))))))))

(defn- extract-referenced-symbols-from-node
  [cst-node]
  (try
    (->> (walk-container-nodes (n/children cst-node))
         (into #{}))
    (catch Exception _ #{})))

;; ---------------------------------------------------------------------------
;; Main parsing
;; ---------------------------------------------------------------------------

(defn parse-file
  [file-path]
  (let [lang (cond
               (clojure-file? file-path) "clojure"
               (edn-file? file-path)     "edn"
               :else                     "unknown")]
    (try
      (let [file-string (slurp file-path)]
        (if (s/blank? file-string)
          {:result/chunks [] :result/errors []}
          (let [top-level-forms (top-level-forms-cst file-string)]
            (if (nil? top-level-forms)
              {:result/chunks []
               :result/errors [{:error/file file-path
                                :error/line   nil
                                :error/column nil
                                :error/message "Failed to parse file: syntax error"}]}
              (loop [remaining top-level-forms
                     chunks []
                     errors []]
                (if-let [form (first remaining)]
                  (let [form-str (:string form)
                        start-line (:start-line form)
                        end-line (compute-end-line form-str start-line)
                        ftype (classify-form-type form-str)
                        nm (when ftype (extract-form-name form-str))
                        vis (form-visibility form-str)
                        cst-node (:cst-node form)
                        meta-data (when ftype
                                    (extract-form-metadata-from-node cst-node ftype))
                        symbols (when (and ftype (not= :ns ftype))
                                  (extract-referenced-symbols-from-node cst-node))]
                    (recur (rest remaining)
                           (conj chunks
                                 {:chunk/id        (UUID/randomUUID)
                                  :chunk/ns        nil ; will be set by enrich-chunks-with-ns
                                  :chunk/file      file-path
                                  :chunk/type      (or ftype :top-level)
                                  :chunk/name      (when ftype (str nm))
                                  :chunk/source    form-str
                                  :chunk/start-line start-line
                                  :chunk/end-line   end-line
                                  :chunk/visibility (if (and (= ftype :fn) (= vis :private))
                                                      :private
                                                      :public)
                                  :chunk/language  lang
                                  :chunk/metadata  meta-data
                                  :chunk/symbols   symbols
                                  :chunk/hash      (sha-256 form-str)})
                           errors))
                  {:result/chunks chunks
                   :result/errors errors}))))))
      (catch Exception e
        {:result/chunks []
         :result/errors [{:error/file file-path
                          :error/line   nil
                          :error/column nil
                          :error/message (str "Unexpected error: " (.getMessage e))}]}))))

(defn parse-project
  [root-path exclude-regexes]
  (let [extensions #{".clj" ".cljc" ".cljs" ".edn"}]
    (reduce
      (fn [acc ^java.io.File f]
        (let [path (.getPath f)
              lc (s/lower-case path)
              ext (re-find #"(\.[^.]+)$" lc)]
          (if (and ext (extensions (second ext))
                   (.isFile f)
                   (not-any? #(re-find % path) exclude-regexes))
            (let [result (parse-file path)]
              (-> acc
                  (update :chunks into (:result/chunks result))
                  (update :errors into (:result/errors result))))
            acc)))
      {:chunks [] :errors []}
      (file-seq (io/file root-path)))))

;; ---------------------------------------------------------------------------
;; Symbol record extraction from CodeChunk
;; ---------------------------------------------------------------------------

(defn chunk->symbol-record
  [chunk ns-name]
  (when (:chunk/name chunk)
    (let [sym-name (symbol (str ns-name "/" (:chunk/name chunk)))
          meta (:chunk/metadata chunk)
          ftype (:chunk/type chunk)]
      (cond-> {:sym/name       sym-name
               :sym/simple     (symbol (:chunk/name chunk))
               :sym/ns         (symbol ns-name)
               :sym/type       ftype
               :sym/file       (:chunk/file chunk)
               :sym/line       (:chunk/start-line chunk)
               :sym/arglists   (:arglists meta)
               :sym/doc        (:doc meta)
               :sym/protocol   nil
               :sym/record     nil
               :sym/tag        (:tag meta)
               :sym/chunk-id   (:chunk/id chunk)
               :sym/visibility (:chunk/visibility chunk)
               :sym/hash       (:chunk/hash chunk)
               :sym/aliases    #{}}
        (or (= :protocol ftype) (= :record ftype))
        (assoc :sym/protocol-name
               (when (= :protocol ftype) (:chunk/name chunk))
               :sym/record-name
               (when (= :record ftype) (:chunk/name chunk)))))))

;; ---------------------------------------------------------------------------
;; Top-level ns info extraction
;; ---------------------------------------------------------------------------

(defn- extract-ns-name
  [chunks]
  (let [ns-chunk (first (filter #(= :ns (:chunk/type %)) chunks))]
    (or (and ns-chunk (extract-form-name (:chunk/source ns-chunk)))
        "unknown")))

(defn enrich-chunks-with-ns
  [parse-result]
  (let [ns-name (extract-ns-name (:result/chunks parse-result))]
    (update parse-result :result/chunks
            (fn [chunks]
              (mapv (fn [chunk]
                      (if (nil? (:chunk/ns chunk))
                        (assoc chunk :chunk/ns ns-name)
                        chunk))
                    chunks)))))

(defn extract-symbols
  [parse-result]
  (let [ns-name (:chunk/ns (first (:result/chunks parse-result)))]
    (when ns-name
      (into []
            (keep #(chunk->symbol-record % ns-name))
            (:result/chunks parse-result)))))

;; ---------------------------------------------------------------------------
;; Convenience: enrich all chunks from a project result (grouped by file)
;; ---------------------------------------------------------------------------

(defn enrich-project-chunks
  [project-result]
  (let [by-file (group-by :chunk/file (:chunks project-result))]
    (reduce-kv (fn [acc _file chunks]
                 (let [file-result (enrich-chunks-with-ns
                                    {:result/chunks chunks
                                     :result/errors (:errors project-result)})
                       enriched (:result/chunks file-result)]
                   (update acc :chunks into enriched)))
               {:chunks [] :errors (:errors project-result)}
               by-file)))

;; ---------------------------------------------------------------------------
;; Convenience: parse + enrich
;; ---------------------------------------------------------------------------

(defn parse-file-full
  [file-path]
  (let [result (parse-file file-path)
        enriched (enrich-chunks-with-ns result)]
    (assoc enriched :symbols (extract-symbols enriched))))