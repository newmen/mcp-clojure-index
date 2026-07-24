(ns mcp.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
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
;; Top-level form matching via node string inspection
;; ---------------------------------------------------------------------------

(defn- first-symbol-of-form
  [^String s]
  (let [trimmed (str/trim s)]
    (when (.startsWith trimmed "(")
      (try
        (let [after-paren (.substring trimmed 1)
              first-form (re-find #"[^\s()]+" after-paren)]
          (when first-form
            (symbol first-form)))
        (catch Exception _ nil)))))

(defn- classify-form-type
  [s]
  (when-let [sym (first-symbol-of-form s)]
    (case sym
      defn        :fn
      defn-       :fn
      defmacro    :macro
      defprotocol :protocol
      defrecord   :record
      deftype     :record
      def         :var
      defonce     :var
      ns          :ns
      nil)))

(defn- form-visibility
  [s]
  (let [trimmed (str/triml s)]
    (if (.startsWith trimmed "(defn-")
      :private
      :public)))

(defn- extract-form-name
  [s]
  (try
    (let [trimmed (str/triml s)
          after-paren (subs trimmed 1)
          after-open (str/triml after-paren)
          parts (str/split after-open #"\s+" 3)]
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

(defn- count-lines
  [s]
  (max 1 (count (str/split-lines s))))

(defn- compute-end-line
  [s start-line]
  (+ start-line (dec (count-lines s))))

;; ---------------------------------------------------------------------------
;; Line-based top-level form extraction
;; ---------------------------------------------------------------------------

(defn extract-top-level-forms-line-based
  [^String s]
  (let [len (.length s)]
    (loop [i 0
           depth 0
           in-str? false
           in-comment? false
           line 1
           col 1
           start-line 1
           forms []
           buf (StringBuilder.)]
      (if (>= i len)
        (let [remaining (.toString buf)]
          (if (pos? (count remaining))
            (conj forms {:string remaining :start-line start-line})
            forms))
        (let [ch (.charAt s i)
              ni (inc i)
              nc (inc col)]
          (cond
            (and in-comment? (not= ch \newline))
            (recur ni depth true in-comment? line nc start-line forms buf)

            (and in-comment? (= ch \newline))
            (recur ni depth false in-comment? (inc line) 1 start-line forms (.append buf ch))

            (and in-str? (or (not= ch \") (and (> i 0) (= (.charAt s (dec i)) \\))))
            (recur ni depth true in-comment? line nc start-line forms (.append buf ch))

            in-str?
            (recur ni depth false in-comment? line nc start-line forms (.append buf ch))

            (= ch \;)
            (recur ni depth true in-comment? line nc start-line forms buf)

            (= ch \")
            (recur ni depth true in-comment? line nc start-line forms (.append buf ch))

            (= ch \()
            (let [new-d (inc depth)]
              (if (= 1 new-d)
                (let [nb (StringBuilder.)]
                  (.append nb ch)
                  (recur ni new-d false in-comment? line nc line forms nb))
                (do
                  (.append buf ch)
                  (recur ni new-d false in-comment? line nc start-line forms buf))))

            (= ch \))
            (let [new-d (dec depth)]
              (.append buf ch)
              (if (zero? new-d)
                (recur ni new-d false in-comment? line nc 0
                       (conj forms {:string (.toString buf) :start-line start-line})
                       (StringBuilder.))
                (recur ni new-d false in-comment? line nc start-line forms buf)))

            (= ch \newline)
            (recur ni depth false in-comment? (inc line) 1 start-line forms (.append buf ch))

            :else
            (recur ni depth false in-comment? line nc start-line forms (.append buf ch))))))))

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
        (if (str/blank? file-string)
          {:result/chunks [] :result/errors []}
          (let [top-level-forms (extract-top-level-forms-line-based file-string)]
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
                 :result/errors errors})))))
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