(ns mcp.config
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)
           (java.lang System)))

(set! *warn-on-reflection* true)

(def ^:private ^:dynamic *getenv*
  "Reads an environment variable; a dynamic var wrapping System/getenv for
   testability. In tests, can be rebound via binding or with-redefs.

  String -> IO (Maybe String)"
  (fn [^String env] (System/getenv env)))

(def ^:private ^:dynamic *user-dir-fn*
  "Reads the working directory; a dynamic var wrapping
   System/getProperty \"user.dir\" for testability. In tests, can be
   rebound via binding or with-redefs.

  () -> IO String"
  (fn [] (System/getProperty "user.dir")))

(defn- pattern->regex
  "Compiles an exclude pattern into a regex in which `.` matches only a dot.

  String -> Pattern"
  [pattern]
  (let [escaped (st/replace pattern "." "\\.")]
    (re-pattern escaped)))

;; Config: the defaults below, the file's keys and the fields computed by
;; `resolve-config` (:index/root-path, :qdrant/collection,
;; :embedding/cache-dir); :index/exclude holds compiled regexes.

(s/def ::config
  (s/keys :opt [:qdrant/host :qdrant/port :qdrant/recreate? :qdrant/collection
                :ollama/url :ollama/embedding-model
                :reranker/url :reranker/model
                :server/transport
                :index/root-path :index/include-extensions :index/exclude
                :embedding/cache-dir
                :search/top-k :search/re-rank-top :search/min-relevance
                :output/limit :output/max-chars
                :doc/signature]))

(def defaults
  "The config values used where the config file leaves a key out. There is
   no `:doc/signature`: without it there are no Type signatures.

  Config"
  {:qdrant/host       "localhost"
   :qdrant/port       6333
   :qdrant/recreate?  false
   :ollama/url        "http://localhost:11434/v1"
   :ollama/embedding-model "bge-m3"
   :reranker/url      "http://localhost:8012/v1"
   :reranker/model    "qllama/bge-reranker-v2-m3"
   :server/transport  :stdio
   :index/include-extensions [".clj" ".cljc" ".cljs" ".edn"]
   :index/exclude     []
   :search/top-k      100
   :search/re-rank-top 10
   :search/min-relevance 0.05
   :output/limit      50
   :output/max-chars  4000})

(defn compile-exclude-patterns
  "Compiles string exclude patterns into regexes.

  [String] -> [Pattern]"
  [exclude-strings]
  (mapv pattern->regex exclude-strings))

(defn setting
  "The value of `setting-key` in `cfg`, or its value in `defaults` when
   `cfg` has none.

  Config -> Keyword -> Any"
  [cfg setting-key]
  (let [value (get cfg setting-key)]
    (if (nil? value) (get defaults setting-key) value)))

(defn- signature-pattern-error
  "Why the `:pattern` regex of a `:doc/signature` strategy cannot find a
   Type signature: it does not compile or has no capture group. Nil when
   it can, or for another strategy.

  Maybe SignatureStrategy -> Maybe String"
  [{:keys [strategy regex]}]
  (when (= :pattern strategy)
    (let [pattern (try (re-pattern (str regex)) (catch Exception _error nil))]
      (cond
        (nil? pattern) (str ":doc/signature :pattern regex does not compile: " regex)
        (zero? (.groupCount ^java.util.regex.Matcher (re-matcher pattern "")))
        (str ":doc/signature :pattern regex needs a capture group for the Type signature: " regex)))))

(defn- check-signature-strategy
  "Returns `cfg` unchanged, or throws when its `:doc/signature` strategy
   cannot find a Type signature (see `signature-pattern-error`).

  Config -> Config"
  [cfg]
  (when-let [error (signature-pattern-error (:doc/signature cfg))]
    (throw (ex-info error {:doc/signature (:doc/signature cfg)})))
  cfg)

(defn load-config
  "Reads the config file at `config-path` over `defaults`, adds the
   directories that are always excluded and compiles the exclude patterns.

  Throws when the `:doc/signature` strategy is a `:pattern` whose regex
  does not compile or has no capture group.

  String -> IO Config"
  [config-path]
  (with-open [r (io/reader config-path)]
    (let [file-config (edn/read (PushbackReader. r))]
      (-> (merge defaults file-config)
          check-signature-strategy
          (update :index/exclude conj
                  ".calva" ".claude" ".clj-kondo" ".cpcache" ".git"
                  ".kilo" ".lsp" ".mcp" "logs" "target" "tmp")
          (update :index/exclude set)
          (update :index/exclude compile-exclude-patterns)))))

(defn- not-blank
  "The string itself unless it is nil or blank.

  Maybe String -> Maybe String"
  [^String s]
  (when (and s (not (st/blank? s))) s))

(defn- non-root-path
  "The path itself if it is a non-blank string and not the root directory.

  Maybe String -> Maybe String"
  [^String path]
  (when (and path
             (not (st/blank? path))
             (not= path "/"))
    path))

(defn resolve-config
  "Fills in the computed config fields by applying the priority chains
   below. Must be called after `load-config`.

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
     3. \".mcp/embedding-cache\" (fallback)

  Config -> IO Config"
  [cfg]
  (let [get-env *getenv*
        root-path (or (:index/root-path cfg)
                      (non-root-path (get-env "QD_PROJECT_ROOT"))
                      (non-root-path (get-env "VSCODE_CWD"))
                      (*user-dir-fn*)
                      ".")
        norm-root (st/replace root-path #"/$" "")
        collection (or (:qdrant/collection cfg)
                       (not-blank (get-env "QD_COLLECTION"))
                       (str (last (st/split norm-root #"/")) "-collection")
                       "clojure-code-index")
        cache-dir (or (:embedding/cache-dir cfg)
                      (str norm-root "/.mcp/embedding-cache"))]
    (assoc cfg
           :index/root-path norm-root
           :qdrant/collection collection
           :embedding/cache-dir cache-dir)))

(defn validate-config
  "Returns `config` unchanged, or throws when `:index/root-path` is missing
   or `:index/include-extensions` is not a collection.

  Config -> Config"
  [config]
  (when-not (:index/root-path config)
    (throw (ex-info "Missing :index/root-path in config" {:config config})))
  (when-not (coll? (:index/include-extensions config))
    (throw (ex-info ":index/include-extensions must be a collection" {:config config})))
  config)