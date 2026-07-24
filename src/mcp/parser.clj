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
              (when (>= (count parts) 3)
                (let [third ^String (nth parts 2)]
                  (when (and third (not= "" third))
                    third)))
              second)))))
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
                        vis (form-visibility s)]
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
                                  :chunk/metadata  {}
                                  :chunk/symbols   #{}
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
    (let [sym-name (symbol (str ns-name "/" (:chunk/name chunk)))]
      {:sym/name       sym-name
       :sym/simple     (symbol (:chunk/name chunk))
       :sym/ns         (symbol ns-name)
       :sym/type       (:chunk/type chunk)
       :sym/file       (:chunk/file chunk)
       :sym/line       (:chunk/start-line chunk)
       :sym/arglists   (:arglists (:chunk/metadata chunk))
       :sym/doc        (:doc (:chunk/metadata chunk))
       :sym/protocol   nil
       :sym/record     nil
       :sym/tag        nil
       :sym/chunk-id   (:chunk/id chunk)
       :sym/visibility (:chunk/visibility chunk)
       :sym/aliases    #{}})))

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
;; Convenience: parse + enrich
;; ---------------------------------------------------------------------------

(defn parse-file-full
  [file-path]
  (let [result (parse-file file-path)]
    (-> result
        enrich-chunks-with-ns
        (assoc :symbols (extract-symbols result)))))