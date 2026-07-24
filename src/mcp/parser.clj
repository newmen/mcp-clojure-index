(ns mcp.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:import (java.security MessageDigest)
           (java.util UUID)
           (java.math BigInteger)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn sha-256
  [s]
  (let [^MessageDigest digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes ^String s "UTF-8"))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

;; ---------------------------------------------------------------------------
;; File detection
;; ---------------------------------------------------------------------------

(defn clojure-file?
  [path]
  (let [s (if (instance? java.io.File path) (.getPath ^java.io.File path) path)
        lc (.toLowerCase ^String s)]
    (or (.endsWith lc ".clj")
        (.endsWith lc ".cljc")
        (.endsWith lc ".cljs"))))

(defn edn-file?
  [path]
  (let [s (if (instance? java.io.File path) (.getPath ^java.io.File path) path)]
    (.endsWith (.toLowerCase ^String s) ".edn")))

;; ---------------------------------------------------------------------------
;; Top-level form matching
;; ---------------------------------------------------------------------------

(defn- classify-form-type
  [s]
  (let [trimmed (s/triml s)]
    (cond
      (.startsWith trimmed "(defn ")       :fn
      (.startsWith trimmed "(defn-")       :fn
      (.startsWith trimmed "(defmacro ")   :macro
      (.startsWith trimmed "(defprotocol ") :protocol
      (.startsWith trimmed "(defrecord ")  :record
      (.startsWith trimmed "(deftype ")    :record
      (.startsWith trimmed "(def ")        :var
      (.startsWith trimmed "(defonce ")    :var
      (.startsWith trimmed "(ns ")         :ns
      :else nil)))

(defn- form-visibility
  [s]
  (if (.startsWith (s/triml s) "(defn-")
    :private
    :public))

(defn- strip-trailing-delims
  [^String s]
  (let [s (s/replace s #"[\)\]\}]" "")]
    (s/replace s #"^[\(\[\{]" "")))

(defn- extract-form-name
  [s]
  (try
    (let [trimmed (s/triml s)
          after-paren (subs trimmed 1)
          after-open (s/triml after-paren)
          parts (s/split after-open #"\s+" 3)]
      (when (>= (count parts) 2)
        (let [second ^String (nth parts 1)]
          (when (and second (not= "" second))
            (if (.startsWith second "^")
              ;; metadata prefix — find the name by skipping meta patterns
              (let [rest (nth parts 2 "")
                    rest-parts (s/split rest #"\s+")]
                (when (seq rest-parts)
                  (let [name-candidate (first rest-parts)]
                    (when (and name-candidate (not= "" name-candidate))
                      (strip-trailing-delims name-candidate)))))
              (strip-trailing-delims second))))))
    (catch Exception _ nil)))

(defn- line-count
  [^String s]
  (if (zero? (count s))
    0
    (loop [i 0
           cnt 1]
      (let [idx (.indexOf s (int \newline) i)]
        (if (neg? idx)
          cnt
          (recur (inc idx) (inc cnt)))))))

;; ---------------------------------------------------------------------------
;; CST-based top-level form extraction via rewrite-clj
;; ---------------------------------------------------------------------------

(defn- form-tag?
  [tag]
  (#{:list :map :vector :set} tag))

(defn- top-level-forms-cst
  [source]
  (try
    (let [zloc (z/of-string* source {:track-position? true})
          children (n/children (z/node zloc))]
      (loop [remaining children
             line (int 1)
             forms []]
        (if-let [child (first remaining)]
          (let [tag (n/tag child)
                cstr (n/string child)
                lines-in-str (line-count cstr)]
            (if (form-tag? tag)
              (recur (rest remaining)
                     (int (+ line (dec lines-in-str)))
                     (conj forms {:string cstr :start-line line}))
              (recur (rest remaining)
                     (int (+ line (dec lines-in-str)))
                     forms)))
          forms)))
    (catch Exception _ nil)))

(defn- compute-end-line
  [s start-line]
  (let [nl (if (s/blank? s) 1 (line-count s))]
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
  (when (and (= :fn ftype) (>= (count children-nodes) 2))
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

(defn- extract-form-metadata
  [source-str ftype]
  (try
    (let [zloc (z/of-string* source-str {:track-position? true})
          node (z/node zloc)
          top-children (n/children node)
          form-node (first top-children)
          children (when form-node (n/children form-node))
          ;; filter out whitespace nodes to get meaningful children
          meaningful (remove (fn [c] (#{:whitespace :newline} (n/tag c))) children)
          body-start (drop 2 meaningful)
          body-strings (keep node-sexpr-safe body-start)
          doc (when (and ftype (not= :ns ftype) (not= :protocol ftype))
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

(defn- extract-referenced-symbols
  [source-str]
  (try
    (let [zloc (z/of-string* source-str {:track-position? true})
          node (z/node zloc)
          top-children (n/children node)
          form-node (first top-children)]
      (when form-node
        (->> (tree-seq n/children seq (n/children form-node))
             (filter #(= :symbol (n/tag %)))
             (map node-sexpr-safe)
             (remove nil?)
             (into #{}))))
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
(let [s (:string form)
                          start-line (:start-line form)
                          end-line (compute-end-line s start-line)
                          ftype (classify-form-type s)
                          nm (when ftype (extract-form-name s))
                          vis (form-visibility s)
                          meta-data (when ftype (extract-form-metadata s ftype))
                          symbols (when (and ftype (not= :ns ftype))
                                    (extract-referenced-symbols s))]
                      (recur (rest remaining)
                             (conj chunks
                                   {:chunk/id        (UUID/randomUUID)
                                    :chunk/ns        nil
                                    :chunk/file      file-path
                                    :chunk/type      (or ftype :top-level)
                                    :chunk/name      (when ftype (str nm))
                                    :chunk/source    s
                                    :chunk/start-line start-line
                                    :chunk/end-line   end-line
                                    :chunk/visibility (if (and (= ftype :fn) (= vis :private))
                                                        :private
                                                        :public)
                                    :chunk/language  lang
                                    :chunk/metadata  meta-data
                                    :chunk/symbols   symbols
                                    :chunk/hash      (sha-256 s)})
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
              lc (.toLowerCase path)
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
    (if ns-chunk
      (or (extract-form-name (:chunk/source ns-chunk)) "unknown")
      "unknown")))

(defn enrich-chunks-with-ns
  [parse-result]
  (let [ns-name (or (extract-ns-name (:result/chunks parse-result)) "unknown")]
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
    (reduce-kv (fn [acc file chunks]
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