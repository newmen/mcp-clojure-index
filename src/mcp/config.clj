(ns mcp.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)))

(defn- pattern->regex
  [pattern]
  (let [escaped (str/replace pattern "." "\\.")]
    (re-pattern escaped)))

(def defaults
  {:qdrant/host       "localhost"
   :qdrant/port       6333
   :qdrant/collection "clojure-code-index"
   :qdrant/recreate?  false
   :ollama/url        "http://localhost:11434/v1"
   :ollama/embedding-model "vishalraj/nomic-embed-code"
   :ollama/reranker-model  "qllama/bce-reranker-base_v1"
   :embedding/cache-dir    ".mcp/embedding-cache"
   :server/transport  :stdio
   :server/port       8080
   :index/root-path   "."
   :index/include-extensions [".clj" ".cljc" ".cljs" ".edn"]
   :index/exclude     ["target" ".git" ".lsp" ".clj-kondo"]
   :search/top-k      100
   :search/re-rank-top 10})

(defn compile-exclude-patterns
  "Convert string exclude patterns to compiled regex patterns."
  [exclude-strings]
  (mapv pattern->regex exclude-strings))

(defn load-config
  [config-path]
  (with-open [r (io/reader config-path)]
    (let [file-config (edn/read (PushbackReader. r))
          merged (merge defaults file-config)]
      (update merged :index/exclude compile-exclude-patterns))))

(defn validate-config
  [config]
  (when-not (:index/root-path config)
    (throw (ex-info "Missing :index/root-path in config" {:config config})))
  (when-not (coll? (:index/include-extensions config))
    (throw (ex-info ":index/include-extensions must be a collection" {:config config})))
  config)