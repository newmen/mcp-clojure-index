(ns mcp.config
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)
           (java.lang System)))

(set! *warn-on-reflection* true)

(def ^:private ^:dynamic *getenv*
  "Dynamic var wrapping System/getenv for testability.
   In tests, can be rebound via binding or with-redefs."
  (fn [^String env] (System/getenv env)))

(def ^:private ^:dynamic *user-dir-fn*
  "Dynamic var wrapping System/getProperty \"user.dir\" for testability.
   In tests, can be rebound via binding or with-redefs."
  (fn [] (System/getProperty "user.dir")))

(defn- pattern->regex
  [pattern]
  (let [escaped (s/replace pattern "." "\\.")]
    (re-pattern escaped)))

(def defaults
  {:qdrant/host       "localhost"
   :qdrant/port       6333
   :qdrant/recreate?  false
   :ollama/url        "http://localhost:11434/v1"
   :ollama/embedding-model "vishalraj/nomic-embed-code"
   :ollama/reranker-model  "qllama/bce-reranker-base_v1"
   :server/transport  :stdio
   :server/port       8080
   :index/include-extensions [".clj" ".cljc" ".cljs" ".edn"]
   :index/exclude     ["target" ".git" ".lsp" ".clj-kondo" ".calva" ".kilo" ".mcp"]
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

(defn- not-blank
  [^String s]
  (when (and s (not (s/blank? s))) s))

(defn- non-root-path
  "Return the path if it is a non-blank string and not the root directory."
  [^String path]
  (when (and path
             (not (s/blank? path))
             (not= path "/"))
    path))

(defn resolve-config
  "Resolve computed config fields by applying the priority chain.
   Must be called after load-config.

   Priority for :index/root-path:
     1. Explicit value from config.edn
     2. QD_PROJECT_ROOT env (must be a real directory, not root)
     3. VSCODE_CWD env (must be a real directory, not root)
     4. user.dir system property
     5. \".\" (fallback)

   Priority for :qdrant/collection:
     1. Explicit value from config.edn
     2. QD_COLLECTION env
     3. Computed from last segment of root-path
     4. \"clojure-code-index\" (fallback)

   Priority for :embedding/cache-dir:
     1. Explicit value from config.edn
     2. Computed from root-path
     3. \".mcp/embedding-cache\" (fallback)"
  [cfg]
  (let [get-env *getenv*
        root-path (or (:index/root-path cfg)
                      (non-root-path (get-env "QD_PROJECT_ROOT"))
                      (non-root-path (get-env "VSCODE_CWD"))
                      (*user-dir-fn*)
                      ".")
        norm-root (s/replace root-path #"/$" "")
        collection (or (:qdrant/collection cfg)
                       (not-blank (get-env "QD_COLLECTION"))
                       (str (last (s/split norm-root #"/")) "-collection")
                       "clojure-code-index")
        cache-dir (or (:embedding/cache-dir cfg)
                      (str norm-root "/.mcp/embedding-cache"))]
    (assoc cfg
           :index/root-path norm-root
           :qdrant/collection collection
           :embedding/cache-dir cache-dir)))

(defn validate-config
  [config]
  (when-not (:index/root-path config)
    (throw (ex-info "Missing :index/root-path in config" {:config config})))
  (when-not (coll? (:index/include-extensions config))
    (throw (ex-info ":index/include-extensions must be a collection" {:config config})))
  config)