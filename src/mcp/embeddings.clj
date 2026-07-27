(ns mcp.embeddings
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [mcp.config :as config])
  (:import (java.io PushbackReader File)
           (java.nio.file Files StandardCopyOption)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Dynamic config for API functions
;; Architecture interface: (generate texts), (rerank query candidates)
;; Uses a dynamic var so callers don't pass config explicitly.
;; ---------------------------------------------------------------------------

(defonce ^:dynamic *config* nil)

(def ^:private batch-size 50)
(def ^:private embedding-timeout 300000)

(defn- get-config
  []
  (or *config* config/defaults))

;; ---------------------------------------------------------------------------
;; Embedding text construction
;; ---------------------------------------------------------------------------

(defn build-embedding-text
  [chunk]
  (let [ns-name (or (:chunk/ns chunk) "")
        sym-name (or (:chunk/name chunk) "")
        meta (:chunk/metadata chunk)
        doc (:doc meta)
        source (:chunk/source chunk "")]
    (s/join "\n"
            (cond-> ["Language:" "Clojure" ""
                     "Namespace:" ns-name ""
                     "Symbol:" sym-name]
                    (seq doc) (conj "" "Docstring:" doc)
                    true (conj "" "Code:" source)))))

;; ---------------------------------------------------------------------------
;; Embedding cache (hash -> vector) — thread-safe via atomic write
;; ---------------------------------------------------------------------------

(defn- cache-dir
  [cfg]
  (let [dir (or (:embedding/cache-dir cfg) ".mcp/embedding-cache")]
    (io/file dir)))

(defn- cache-file
  [cfg hash-str]
  (io/file (cache-dir cfg) (str hash-str ".edn")))

(defn cached-embedding
  [cfg hash-str]
  (let [^File f (cache-file cfg hash-str)]
    (when (.exists f)
      (try
        (with-open [r (io/reader f)]
          (edn/read (PushbackReader. r)))
        (catch Exception _ nil)))))

(defn cache-embedding!
  [cfg hash-str embedding]
  (let [^File dir (cache-dir cfg)]
    (.mkdirs dir)
    (let [^File target (cache-file cfg hash-str)
          tmp (io/file (str (.getAbsolutePath target) ".tmp"))]
      (spit tmp (pr-str embedding))
      (try
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (catch Exception _
          (io/copy tmp target)
          (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; Ollama API helpers
;; ---------------------------------------------------------------------------

(defn- ollama-embed-url
  [cfg]
  (let [base (or (:ollama/url cfg) "http://localhost:11434/v1")]
    (str (s/replace base #"/$" "") "/embeddings")))

(defn- embedding-model
  [cfg]
  (or (:ollama/embedding-model cfg) "vishalraj/nomic-embed-code"))

(defn- reranker-model
  [cfg]
  (or (:ollama/reranker-model cfg) "qllama/bce-reranker-base_v1"))

(defn- http-post-json
  [url body-map]
  (let [response (http/post url {:content-type "application/json"
                                 :body (json/write-str body-map)
                                 :socket-timeout embedding-timeout
                                 :connection-timeout 10000})]
    (update response :body #(json/read-str % :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; Generate embeddings from text strings
;; Architecture interface: (generate texts) -> seq of vectors
;; ---------------------------------------------------------------------------

(defn- extract-embeddings
  [data]
  (when-let [items (:data data)]
    (mapv (fn [item] (mapv double (:embedding item))) items)))

(defn generate
  ([texts]
   (generate texts (get-config)))
  ([texts cfg]
   (when (seq texts)
     (let [url (ollama-embed-url cfg)
           model (embedding-model cfg)
           batcher (fn [batch]
                     (let [response (http-post-json url {:model model :input (vec batch)})
                           data (:body response)
                           embeddings (extract-embeddings data)]
                       (when (nil? embeddings)
                         (throw (ex-info "Ollama returned no embeddings"
                                  {:url url :model model :response data})))
                       embeddings))
           batches (partition-all batch-size texts)
           results (mapcat batcher batches)]
       (vec results)))))

;; ---------------------------------------------------------------------------
;; Generate embeddings from chunks with hash-based caching
;; ---------------------------------------------------------------------------

(defn generate-from-chunks
  [cfg chunks]
  (let [texts (mapv build-embedding-text chunks)
        hashes (mapv :chunk/hash chunks)
        cached (mapv (partial cached-embedding cfg) hashes)
        uncached-idx (vec (keep-indexed (fn [i v] (when (nil? v) i)) cached))]
    (if (empty? uncached-idx)
      (mapv vector chunks cached)
      (let [idx->pos (into {} (map-indexed (fn [pos i] [i pos]) uncached-idx))
            uncached (mapv (fn [i] {:text (nth texts i) :hash (nth hashes i)})
                           uncached-idx)
            embeddings (binding [*config* cfg]
                         (generate (mapv :text uncached)))]
        (run! (fn [[{:keys [hash]} emb]]
                (when hash
                  (cache-embedding! cfg hash emb)))
              (map vector uncached embeddings))
        (mapv (fn [i]
                [(nth chunks i)
                 (if-let [pos (get idx->pos i)]
                   (nth embeddings pos)
                   (nth cached i))])
              (range (count chunks)))))))

;; ---------------------------------------------------------------------------
;; Reranking
;; Architecture interface: (rerank query candidates) -> seq of {:text :score :re-rank}
;;
;; NOTE: This is a temporary implementation that uses the embedding endpoint.
;; A proper cross-encoder should call Ollama's /api/generate with the BCE
;; reranker model using the standard "query: Q passage: P" format and extract
;; logits from the response. This will be replaced in a future task.
;; ---------------------------------------------------------------------------

(defn rerank
  ([query candidates]
   (rerank query candidates (get-config)))
  ([query candidates cfg]
   (let [url (ollama-embed-url cfg)
         model (reranker-model cfg)
         texts (mapv :text candidates)]
     (if (empty? texts)
       []
       (try
         (let [response (http-post-json url {:model model
                                              :input (mapv (fn [_] (str "query: " query)) texts)
                                              :input2 (mapv (fn [t] (str "passage: " t)) texts)})
               data (:body response)]
           (if-let [embeddings (:embeddings data)]
             (mapv (fn [c e] (assoc c :re-rank (float (first e)))) candidates embeddings)
             (mapv #(assoc % :re-rank (:score %)) candidates)))
         (catch Exception _
           (mapv #(assoc % :re-rank (:score %)) candidates)))))))