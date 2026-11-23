(ns mcp.config
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [clojure.edn :as edn]
            [mcp.docstring :as docstring])
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

;; Settings: the defaults below, the file's keys and the fields computed by
;; `resolve-config` (:index/root-path, :qdrant/collection,
;; :embedding/cache-dir), as written: :index/exclude and :search/exclude
;; hold strings.
;; Config: Settings after `settings->config`: :index/exclude and
;; :search/exclude hold compiled regexes, the always-excluded directories
;; added to :index/exclude.

(s/def ::config
  (s/keys :opt [:qdrant/host :qdrant/port :qdrant/recreate? :qdrant/collection
                :ollama/url :ollama/embedding-model
                :reranker/url :reranker/model
                :server/transport
                :index/root-path :index/include-extensions :index/exclude
                :embedding/cache-dir
                :search/exclude :search/top-k :search/re-rank-top :search/min-relevance
                :output/limit :output/max-chars
                :doc/signature]))

(s/def ::settings ::config)

(def ^:private always-excluded
  "The exclude patterns added to `:index/exclude` whatever the server
   config and the Project config say.

  [String]"
  [".calva" ".claude" ".clj-kondo" ".cpcache" ".git"
   ".kilo" ".lsp" ".mcp" "logs" "target" "tmp"])

(def ^:private config-keys
  "Every key a server config or a Project config may hold.

  #{Keyword}"
  (let [[_keys & {:keys [opt]}] (s/form ::config)]
    (set opt)))

(defn- pattern->regex
  "Compiles an exclude pattern into a regex in which `.` matches only a dot.

  String -> Pattern"
  [pattern]
  (let [escaped (st/replace pattern "." "\\.")]
    (re-pattern escaped)))

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
   :search/exclude    []
   :search/top-k      100
   :search/re-rank-top 10
   :search/min-relevance 0.3
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

(defn- signature-strategy-error
  "Why the `:doc/signature` value cannot find a Type signature: it is not
   a map, names no built-in strategy, or is a `:pattern` whose regex does
   not compile or has no capture group. Nil when it can, or when there is
   no `:doc/signature`.

  Maybe SignatureStrategy -> Maybe String"
  [signature-strategy]
  (when (some? signature-strategy)
    (let [strategy (when (map? signature-strategy) (:strategy signature-strategy))]
      (if (contains? (dissoc (methods docstring/split-signature) :default) strategy)
        (signature-pattern-error signature-strategy)
        (str "unknown :doc/signature strategy " (pr-str strategy))))))

(defn- exclude-pattern-error
  "Why an exclude pattern list cannot be used: it is not a sequence of
   strings, or one of them does not compile. Nil when it can.

  Keyword -> Any -> Maybe String"
  [setting-key patterns]
  (if-not (and (sequential? patterns) (every? string? patterns))
    (str setting-key " must be a vector of strings")
    (some (fn [pattern]
            (try (pattern->regex pattern) nil
                 (catch java.util.regex.PatternSyntaxException e
                   (str setting-key " pattern does not compile: " pattern
                        " (" (.getDescription e) ")"))))
          patterns)))

(defn- setting-value-error
  "Why `value` cannot be used as the setting `setting-key`, nil when it
   can or when the key needs no check. Covers the keys whose bad value
   would only show up later, in every answer.

  Keyword -> Any -> Maybe String"
  [setting-key value]
  (case setting-key
    :doc/signature (signature-strategy-error value)
    :index/include-extensions (when-not (coll? value)
                                ":index/include-extensions must be a collection")
    (:index/exclude :search/exclude) (exclude-pattern-error setting-key value)
    (:output/limit :output/max-chars) (when-not (pos-int? value)
                                        (str setting-key " must be a positive integer"))
    :search/min-relevance (when-not (number? value)
                            ":search/min-relevance must be a number")
    nil))

(defn- settings-error
  "Why `file-settings`, read from a server config or a Project config,
   cannot be used: not a map, a key the server config cannot hold, or a
   value that cannot be used. Nil when it can.

  Any -> Maybe String"
  [file-settings]
  (if-not (map? file-settings)
    "not a map"
    (let [unknown-keys (sort (remove config-keys (keys file-settings)))]
      (if (seq unknown-keys)
        (str (if (next unknown-keys) "unknown keys " "unknown key ")
             (st/join ", " unknown-keys))
        (some (fn [[setting-key value]] (setting-value-error setting-key value))
              (sort-by key file-settings))))))

(defn- read-edn-file
  "The EDN value in `file`.

  File -> IO Any"
  [^java.io.File file]
  (with-open [file-reader (io/reader file)]
    (edn/read (PushbackReader. file-reader))))

(defn load-settings
  "Reads the server config file at `config-path` over `defaults`, without
   compiling its exclude patterns (see `settings->config`).

  Throws, naming the file's absolute path, when the file cannot be read,
  holds an unknown key or a value that cannot be used (e.g. a `:doc/signature` `:pattern` whose
  regex does not compile or has no capture group).

  String -> IO Settings"
  [config-path]
  (let [file (io/file config-path)
        fail (fn [reason]
               (throw (ex-info (str "invalid server config " (.getAbsolutePath file) ": " reason)
                               {:config-path (.getAbsolutePath file)})))
        file-settings (try (read-edn-file file)
                           (catch Exception e (fail (str "cannot read EDN: " (ex-message e)))))]
    (some-> (settings-error file-settings) fail)
    (merge defaults file-settings)))

(defn settings->config
  "The Config of `settings`: the always-excluded directories added to
   `:index/exclude`, and its patterns and those of `:search/exclude`
   compiled.

  Settings -> Config"
  [settings]
  (-> settings
      (update :index/exclude (comp compile-exclude-patterns vec set (partial concat always-excluded)))
      (update :search/exclude (comp compile-exclude-patterns vec))))

(defn load-config
  "Reads the server config file at `config-path` over `defaults`, adds the
   directories that are always excluded and compiles the exclude patterns.

  Throws as `load-settings` does.

  String -> IO Config"
  [config-path]
  (settings->config (load-settings config-path)))

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
  (when-let [error (setting-value-error :index/include-extensions
                                       (:index/include-extensions config))]
    (throw (ex-info error {:config config})))
  config)

;; ---------------------------------------------------------------------------
;; Project config
;; ---------------------------------------------------------------------------

;; ProjectConfigState: what the server knows of the Project config.
;; {:server-settings Settings  the server config, resolved
;;  :start-settings  Settings  what the server started on; its cold keys stay
;;  :file-settings   Map       the last valid Project config, {} without one
;;  :stamp           [Boolean Long]  presence and mtime at the last read
;;  :error           Maybe String    why the file on disk is not used
;;  :config          Config}   the settings answers are given with

(s/def ::project-config-state
  (s/keys :req-un [::server-settings ::start-settings ::file-settings ::stamp ::config]
          :opt-un [::error]))

;; ProjectConfigRead: one read of the Project config file, {:settings Map}
;; ({} without a file) or {:error String} when it cannot be used.

(s/def ::project-config-read (s/keys :opt-un [::settings ::error]))

;; ProjectConfigNotices: the lines an answer carries about the Project
;; config, {:before Maybe String :after Maybe String}.

(s/def ::before (s/nilable string?))

(s/def ::after (s/nilable string?))

(s/def ::project-config-notices (s/keys :req-un [::before ::after]))

(def project-config-file
  "The Project config's file name, at the project root.

  String"
  ".clojure-index.edn")

(def hot-keys
  "The keys of a Project config that apply from the next answer; every
   other key is read at server start only.

  #{Keyword}"
  #{:index/exclude :index/include-extensions
    :search/exclude :search/min-relevance :output/limit :output/max-chars :doc/signature})

(defn- project-config-path
  "The Project config file of the project `settings` index.

  Settings -> File"
  [settings]
  (io/file (:index/root-path settings) project-config-file))

(defn- file-stamp
  "Whether `file` exists, and its mtime.

  File -> IO [Boolean Long]"
  [^java.io.File file]
  [(.exists file) (.lastModified file)])

(defn- read-project-settings
  "Reads the Project config `file`.

  File -> IO ProjectConfigRead"
  [^java.io.File file]
  (if-not (.exists file)
    {:settings {}}
    (try
      (let [file-settings (read-edn-file file)]
        (if-let [error (settings-error file-settings)]
          {:error error}
          {:settings file-settings}))
      (catch Exception e
        {:error (str "cannot read EDN: " (ex-message e))}))))

(defn- current-settings
  "The settings answers are given with: the start settings, with the hot
   keys of the server config overlaid by the last valid Project config.

  ProjectConfigState -> Settings"
  [{:keys [server-settings start-settings file-settings]}]
  (merge (apply dissoc start-settings hot-keys)
         (select-keys (merge server-settings file-settings) hot-keys)))

(defn- with-current-config
  "`project-config` with its `:config` rebuilt from its settings.

  ProjectConfigState -> ProjectConfigState"
  [project-config]
  (assoc project-config :config (settings->config (current-settings project-config))))

(defn start-project-config
  "Reads the Project config of the project `server-settings` index, as the
   server does at start. A valid file overrides any key; with an invalid
   one the server config alone is used, and `project-config-notices`
   reports it.

  Settings -> IO ProjectConfigState"
  [server-settings]
  (let [file (project-config-path server-settings)
        stamp (file-stamp file)
        {:keys [settings error]} (read-project-settings file)
        file-settings (or settings {})]
    (with-current-config
      (cond-> {:server-settings server-settings
               :start-settings (merge server-settings file-settings)
               :file-settings file-settings
               :stamp stamp}
        error (assoc :error error)))))

(defn refresh-project-config
  "Re-reads the Project config when its presence or mtime changed since
   the last read. A valid file becomes the source of the hot keys; an
   invalid one keeps the previous settings, and `project-config-notices`
   reports it.

  ProjectConfigState -> IO ProjectConfigState"
  [project-config]
  (let [file (project-config-path (:server-settings project-config))
        stamp (file-stamp file)]
    (if (= stamp (:stamp project-config))
      project-config
      (let [{:keys [settings error]} (read-project-settings file)]
        (if error
          (assoc project-config :stamp stamp :error error)
          (-> project-config
              (assoc :stamp stamp :file-settings settings)
              (dissoc :error)
              with-current-config))))))

(defn- restart-keys
  "The cold keys whose value in the last valid Project config (or the
   server config) differs from the one the server started on.

  ProjectConfigState -> [Keyword]"
  [{:keys [server-settings start-settings file-settings]}]
  (let [wanted (merge server-settings file-settings)]
    (->> (concat (keys wanted) (keys start-settings))
         distinct
         (remove hot-keys)
         (remove #(= (get wanted %) (get start-settings %)))
         sort)))

(defn project-config-notices
  "The lines an answer carries about the Project config: `:before` while
   the file is invalid, `:after` while a key that needs a restart differs
   from its running value.

  ProjectConfigState -> ProjectConfigNotices"
  [project-config]
  (let [cold-keys (restart-keys project-config)]
    {:before (when-let [error (:error project-config)]
               (str "project config error in " project-config-file ": " error
                    "; using previous settings"))
     :after (when (seq cold-keys)
              (str project-config-file ": " (st/join ", " cold-keys)
                   " changes after a server restart"))}))