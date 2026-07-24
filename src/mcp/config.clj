(ns mcp.config
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)))

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
   :index/exclude     [#"target" #".git" #".lsp" #".clj-kondo"]
   :search/top-k      100
   :search/re-rank-top 10})

(defn load-config
  [config-path]
  (with-open [r (io/reader config-path)]
    (let [file-config (edn/read (PushbackReader. r))]
      (merge defaults file-config))))

(defn validate-config
  [config]
  (when-not (:index/root-path config)
    (throw (ex-info "Missing :index/root-path in config" {:config config})))
  (when-not (coll? (:index/include-extensions config))
    (throw (ex-info ":index/include-extensions must be a collection" {:config config})))
  config)