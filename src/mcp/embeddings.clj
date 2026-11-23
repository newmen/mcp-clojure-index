(ns mcp.embeddings
  (:require [clojure.string :as st]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
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

;; Embedding: the vector Ollama returns for one text.
;; HttpResponse: a clj-http response whose `:body` is parsed JSON.
;; Timeouts: clj-http's {:socket-timeout :connection-timeout}, in ms.

(s/def ::embedding (s/coll-of double? :kind vector?))

(s/def ::http-response (s/keys :req-un [::status ::body]))

(s/def ::timeouts (s/keys :req-un [::socket-timeout ::connection-timeout]))

(def ^:private batch-size 50)
(def ^:private embedding-timeouts
  {:socket-timeout 300000 :connection-timeout 10000})

(def ^:private rerank-timeouts
  "The reranker is optional: an absent one is given up on quickly.

  Timeouts"
  {:socket-timeout 120000 :connection-timeout 2000})

(def ^:private rerank-max-chars
  "Characters of query plus candidate text the reranker reads. The BCE
   model takes at most 512 tokens per query and document together, and
   the reranker rejects the whole request when one pair is longer; 1000
   characters of Clojure measured at most ~415 tokens. A text starts with
   name, arglists and docstring, so these are always read.

  Long"
  1000)

(defn- get-config
  "The config bound to `*config*`, or the defaults.

  () -> Config"
  []
  (or *config* config/defaults))

;; ---------------------------------------------------------------------------
;; Embedding cache (hash -> vector) — thread-safe via atomic write
;; ---------------------------------------------------------------------------

(defn- cache-dir
  "The directory of the embedding cache.

  Config -> File"
  [cfg]
  (let [dir (or (:embedding/cache-dir cfg) ".mcp/embedding-cache")]
    (io/file dir)))

(defn- cache-file
  "The cache file of the embedding of the text with hash `hash-str`.

  Config -> String -> File"
  [cfg hash-str]
  (io/file (cache-dir cfg) (str hash-str ".edn")))

(defn cached-embedding
  "Reads the cached embedding of the text with hash `hash-str`; nil when it
   is not cached or the cache file is unreadable.

  Config -> String -> IO (Maybe Embedding)"
  [cfg hash-str]
  (let [^File f (cache-file cfg hash-str)]
    (when (.exists f)
      (try
        (with-open [r (io/reader f)]
          (edn/read (PushbackReader. r)))
        (catch Exception _ nil)))))

(defn cache-embedding!
  "Writes `embedding` to the cache under `hash-str`, atomically where the
   file system allows it.

  Config -> String -> Embedding -> IO ()"
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

(defn- endpoint-url
  "The URL of `path` under the API `base-url`, with or without a trailing
   slash.

  String -> String -> String"
  [base-url path]
  (str (st/replace base-url #"/$" "") path))

(defn- ollama-embed-url
  "The URL of Ollama's embeddings endpoint.

  Config -> String"
  [cfg]
  (endpoint-url (or (:ollama/url cfg) "http://localhost:11434/v1") "/embeddings"))

(defn- embedding-model
  "The name of the embedding model.

  Config -> String"
  [cfg]
  (or (:ollama/embedding-model cfg) "vishalraj/nomic-embed-code"))

(defn- reranker-model
  "The name of the reranker model.

  Config -> String"
  [cfg]
  (or (:reranker/model cfg) "qllama/bce-reranker-base_v1"))

(defn- http-post-json
  "Posts `body-map` as JSON to `url`; `timeouts` are clj-http's
   {:socket-timeout :connection-timeout}. The body comes back parsed.

  String -> Map -> Timeouts -> IO HttpResponse"
  [url body-map timeouts]
  (let [response (http/post url (merge {:content-type "application/json"
                                        :body (json/write-str body-map)}
                                       timeouts))]
    (update response :body #(json/read-str % :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; Generate embeddings from text strings
;; Architecture interface: (generate texts) -> seq of vectors
;; ---------------------------------------------------------------------------

(defn- extract-embeddings
  "The embeddings in a parsed response of the embeddings endpoint; nil when
   it has no `:data`.

  Map -> Maybe [Embedding]"
  [data]
  (when-let [items (:data data)]
    (mapv (fn [item] (mapv double (:embedding item))) items)))

(defn generate
  "Asks Ollama for the embeddings of `texts`, in order and in batches; nil
   for no texts. Throws when Ollama answers without embeddings.

  [String] -> IO (Maybe [Embedding])
  [String] -> Config -> IO (Maybe [Embedding])"
  ([texts]
   (generate texts (get-config)))
  ([texts cfg]
   (when (seq texts)
     (let [url (ollama-embed-url cfg)
           model (embedding-model cfg)
           batcher (fn [batch]
                     (let [response (http-post-json url {:model model :input (vec batch)} embedding-timeouts)
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
;; Embeddings of Search units, cached by their hash
;; ---------------------------------------------------------------------------

(defn embed-units
  "Embedding vectors of the Search `units` (see `mcp.search-units`), in
   order. Vectors are cached on disk by the unit's `:hash`; only uncached
   texts are sent to Ollama.

  Config -> [SearchUnit] -> IO [Embedding]"
  [cfg units]
  (let [cached (mapv (comp (partial cached-embedding cfg) :hash) units)
        uncached (vec (remove (comp some? (partial nth cached)) (range (count units))))
        generated (zipmap uncached (generate (mapv (comp :text (partial nth units)) uncached) cfg))]
    (doseq [[unit-index unit-vector] generated]
      (cache-embedding! cfg (:hash (nth units unit-index)) unit-vector))
    (mapv #(or (nth cached %) (get generated %)) (range (count units)))))

;; ---------------------------------------------------------------------------
;; Reranking
;; Architecture interface: (rerank query candidates) -> candidates best first,
;; each with :re-rank (absent when the reranker is unavailable)
;;
;; Ollama cannot run the BCE cross-encoder (no rerank API; its embedding
;; runner crashes on the model), so the reranker is a separate server
;; speaking the /v1/rerank API of llama.cpp's `llama-server --rerank`
;; (also Jina- and Cohere-compatible): request {:model :query :documents},
;; response {:results [{:index i :relevance_score s}]}. llama-server's
;; scores are the model's logits; their sigmoid is the relevance in 0..1,
;; as the BCE model is meant to be read.
;; ---------------------------------------------------------------------------

(defn- rerank-url
  "The URL of the reranker's rerank endpoint.

  Config -> String"
  [cfg]
  (endpoint-url (or (:reranker/url cfg) "http://localhost:8012/v1") "/rerank"))

(defn- scores-in-order
  "The scores of the reranker's `results` by candidate index, or nil
   unless they score each of the `candidate-count` candidates exactly once.

  [{:index Long :relevance_score Double}] -> Long -> Maybe [Double]"
  [results candidate-count]
  (let [by-index (into {} (map (juxt :index :relevance_score)) results)
        scores (mapv by-index (range candidate-count))]
    (when (and (= candidate-count (count results) (count by-index))
               (every? number? scores))
      scores)))

(defn- relevance-scores
  "The reranker's score of each of `texts` for `query`, in order, or nil
   when it is not reachable, fails or answers something else.

  Config -> String -> [String] -> IO (Maybe [Double])"
  [cfg query texts]
  (try
    (-> (http-post-json (rerank-url cfg)
                        {:model (reranker-model cfg) :query query :documents texts}
                        rerank-timeouts)
        (get-in [:body :results])
        (scores-in-order (count texts)))
    (catch Exception e
      (log/debug "Reranker unavailable:" (.getMessage e))
      nil)))

(defn- sigmoid
  "The logistic function of `logit`, a relevance in 0..1.

  Double -> Double"
  [logit]
  (/ 1.0 (+ 1.0 (Math/exp (- (double logit))))))

(defn- truncate-for-rerank
  "The start of `text` that fits in `rerank-max-chars` together with
   `query`.

  String -> Maybe String -> String"
  [query text]
  (let [text (str text)
        max-chars (max 0 (- rerank-max-chars (count query)))]
    (cond-> text
      (> (count text) max-chars) (subs 0 max-chars))))

(defn rerank
  "Orders `candidates` by the reranker's relevance of their `:text` to
   `query`, best first, each with its relevance (0..1) as `:re-rank`.
   When the reranker is unavailable, returns `candidates` as given.

  String -> [Candidate] -> IO [Candidate]
  String -> [Candidate] -> Config -> IO [Candidate]"
  ([query candidates]
   (rerank query candidates (get-config)))
  ([query candidates cfg]
   (if-let [scores (when (seq candidates)
                     (relevance-scores cfg query
                                       (mapv (comp (partial truncate-for-rerank query) :text)
                                             candidates)))]
     (->> (map #(assoc %1 :re-rank (sigmoid %2)) candidates scores)
          (sort-by (comp - :re-rank))
          vec)
     (vec candidates))))
