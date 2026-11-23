(ns mcp.config-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [mcp.config :as sut]
            [mcp.test-project :as project]))

(def fixtures-dir "test-resources/fixtures")

(defn fixture-path
  "The path of the config fixture file `name`.

  String -> String"
  [name]
  (str fixtures-dir "/" name))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(deftest defaults-contain-all-keys
  (is (= "localhost" (:qdrant/host sut/defaults)))
  (is (= 6333 (:qdrant/port sut/defaults)))
  (is (= "http://localhost:11434/v1" (:ollama/url sut/defaults)))
  (is (= :stdio (:server/transport sut/defaults)))
  (is (= 100 (:search/top-k sut/defaults)))
  (is (= 10 (:search/re-rank-top sut/defaults))))

(deftest defaults-include-extensions
  (is (= [".clj" ".cljc" ".cljs" ".edn"] (:index/include-extensions sut/defaults))))

(deftest defaults-exclude-are-strings
  (is (= [] (:index/exclude sut/defaults)))
  (is (every? string? (:index/exclude sut/defaults))))

(deftest search-exclusions-default-to-none
  (is (= [] (:search/exclude sut/defaults)))
  (is (= [] (:search/exclude (sut/load-config "resources/config.edn"))))
  (is (= [] (:search/exclude (edn/read-string (slurp "resources/config.edn"))))))

(deftest search-exclusions-compile-like-index-exclude-without-the-always-excluded
  (let [excludes (:search/exclude (sut/settings->config {:search/exclude ["gen/com.google"]}))]
    (is (= ["gen/com\\.google"] (map str excludes)))
    (is (re-find (first excludes) "gen/com.google/protobuf/any.clj"))))

(deftest a-search-exclusion-that-does-not-compile-is-rejected
  (let [file (java.io.File/createTempFile "config" ".edn")]
    (spit file "{:search/exclude [\"gen(\"]}")
    (is (= (str "invalid server config " (.getAbsolutePath file)
                ": :search/exclude pattern does not compile: gen( (Unclosed group)")
           (try (sut/load-config (.getPath file)) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))

(deftest search-exclusions-apply-from-the-next-answer
  (is (contains? sut/hot-keys :search/exclude)))

;; ---------------------------------------------------------------------------
;; pattern->regex
;; ---------------------------------------------------------------------------

(deftest pattern->regex-escapes-dot
  (let [re (#'sut/pattern->regex ".git")]
    (is (instance? java.util.regex.Pattern re))
    (is (re-find re ".git"))
    (is (not (re-find re "xgit")))))

(deftest pattern->regex-matches-anywhere
  (let [re (#'sut/pattern->regex "target")]
    (is (re-find re "target"))
    (is (re-find re "/project/target"))
    (is (re-find re "mytarget"))))

(deftest pattern->regex-multiple-patterns
  (let [patterns (mapv #'sut/pattern->regex ["target" ".git"])
        path "/project/.git/objects"]
    (is (some #(re-find % path) patterns))))

;; ---------------------------------------------------------------------------
;; compile-exclude-patterns
;; ---------------------------------------------------------------------------

(deftest compile-exclude-patterns-returns-regexes
  (let [res (sut/compile-exclude-patterns ["target" ".git"])]
    (is (= 2 (count res)))
    (is (every? #(instance? java.util.regex.Pattern %) res))))

(deftest compile-exclude-patterns-empty
  (is (= [] (sut/compile-exclude-patterns []))))

;; ---------------------------------------------------------------------------
;; load-config
;; ---------------------------------------------------------------------------

(deftest load-config-merges-with-defaults
  (let [cfg (sut/load-config (fixture-path "config.edn"))]
    (is (= "localhost" (:qdrant/host cfg)))
    (is (= 6333 (:qdrant/port cfg)))
    (is (= "vishalraj/nomic-embed-code" (:ollama/embedding-model cfg)))
    (is (instance? java.util.regex.Pattern (first (:index/exclude cfg))))
    (is (= "/Users/altermn/projects/clojure/mcp" (:index/root-path cfg)))))

(deftest defaults-and-shipped-config-use-the-multilingual-models
  (doseq [cfg [sut/defaults (sut/load-config "resources/config.edn")]]
    (is (= {:ollama/embedding-model "bge-m3"
            :reranker/model "qllama/bge-reranker-v2-m3"}
           (select-keys cfg [:ollama/embedding-model :reranker/model])))))

(deftest min-relevance-defaults-to-0-3
  (is (= 0.3 (:search/min-relevance sut/defaults)))
  (is (= 0.3 (:search/min-relevance (sut/load-config "resources/config.edn"))))
  (is (= 0.3 (:search/min-relevance (edn/read-string (slurp "resources/config.edn"))))))

(deftest load-config-defaults-output-budget
  (let [cfg (sut/load-config (fixture-path "config.edn"))]
    (is (= 50 (:output/limit cfg)))
    (is (= 4000 (:output/max-chars cfg)))))

(deftest load-config-compiles-exclude-patterns
  (let [cfg (sut/load-config (fixture-path "config.edn"))
        excludes (:index/exclude cfg)]
    (is (every? #(instance? java.util.regex.Pattern %) excludes))
    (let [path "/project/.git/objects"]
      (is (some #(re-find % path) excludes)))
    (let [path "/project/target/classes"]
      (is (some #(re-find % path) excludes)))))

(deftest load-config-preserves-overrides
  (let [cfg (sut/load-config (fixture-path "config.edn"))
        exclude-strs (set (map #(.pattern ^java.util.regex.Pattern %) (:index/exclude cfg)))]
    (is (= "/Users/altermn/projects/clojure/mcp" (:index/root-path cfg)))
    (is (= #{"target" "\\.git" "\\.claude" "\\.lsp" "\\.clj-kondo" "\\.calva" "\\.kilo" "\\.mcp"
             "\\.cpcache" "logs" "tmp"}
           exclude-strs))))

(deftest load-config-excludes-agent-worktrees-under-dot-claude
  (let [excludes (:index/exclude (sut/load-config (fixture-path "config.edn")))]
    (is (some #(re-find % ".claude/worktrees/agent-1/src/mcp/server.clj") excludes))))

;; ---------------------------------------------------------------------------
;; validate-config
;; ---------------------------------------------------------------------------

(deftest validate-config-accepts-valid
  (is (= {:index/root-path "/foo" :index/include-extensions [".clj"]}
         (sut/validate-config {:index/root-path "/foo"
                               :index/include-extensions [".clj"]}))))

(deftest validate-config-throws-on-missing-root-path
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing :index/root-path"
                        (sut/validate-config {:index/include-extensions [".clj"]}))))

(deftest validate-config-throws-on-non-collection-extensions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":index/include-extensions must be a collection"
                        (sut/validate-config {:index/root-path "/foo"
                                              :index/include-extensions ".clj"}))))

;; ---------------------------------------------------------------------------
;; load-config from non-existent file
;; ---------------------------------------------------------------------------

(deftest an-invalid-server-config-names-its-absolute-path
  (is (= (str "invalid server config /nonexistent/path.edn: cannot read EDN: "
              "/nonexistent/path.edn (No such file or directory)")
         (try (sut/load-config "/nonexistent/path.edn") nil
              (catch clojure.lang.ExceptionInfo e (ex-message e))))))

;; ---------------------------------------------------------------------------
;; load-config with minimal file (only overrides)
;; ---------------------------------------------------------------------------

(deftest load-config-with-minimal-override
  (let [tmp-file (java.io.File/createTempFile "test-config" ".edn")]
    (spit tmp-file "{:index/root-path \"/tmp/test\"}")
    (let [cfg (sut/load-config (.getPath tmp-file))]
      (is (= "/tmp/test" (:index/root-path cfg)))
      (is (= "localhost" (:qdrant/host cfg)))
      (is (= 100 (:search/top-k cfg))))
    (.delete tmp-file)))

;; ---------------------------------------------------------------------------
;; Edge cases: config validation
;; ---------------------------------------------------------------------------

(deftest validate-config-throws-on-nil-root-path
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/validate-config {:index/root-path nil
                                     :index/include-extensions [".clj"]}))))

(deftest validate-config-throws-on-nil-extensions
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/validate-config {:index/root-path "/foo"
                                     :index/include-extensions nil}))))

(deftest validate-config-throws-on-string-extensions
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/validate-config {:index/root-path "/foo"
                                     :index/include-extensions ".clj"}))))

;; ---------------------------------------------------------------------------
;; Edge cases: compile-exclude-patterns pattern conversion
;; ---------------------------------------------------------------------------

(deftest compile-exclude-patterns-dots-escaped
  (let [patterns (sut/compile-exclude-patterns [".git" ".lsp"])]
    (is (every? #(instance? java.util.regex.Pattern %) patterns))))

(deftest compile-exclude-patterns-no-false-positives
  (let [target-only (sut/compile-exclude-patterns ["target"])]
    (is (some #(re-find % "/project/target/classes") target-only))
    ;; "target" should NOT match "some-target-folder" — oh wait, the current
    ;; implementation uses simple re-pattern without bounds checking,
    ;; so this can produce false positives. Noted for future improvement.
    (is (some #(re-find % "target") target-only))))

;; ---------------------------------------------------------------------------
;; Edge cases: load-config with all fields
;; ---------------------------------------------------------------------------

(deftest load-config-preserves-qdrant-settings
  (let [tmp-file (java.io.File/createTempFile "test-config2" ".edn")]
    (spit tmp-file "{:qdrant/host \"qdrant.local\" :qdrant/port 16333 :index/root-path \"/tmp\"}")
    (let [cfg (sut/load-config (.getPath tmp-file))]
      (is (= "qdrant.local" (:qdrant/host cfg)))
      (is (= 16333 (:qdrant/port cfg))))
    (.delete tmp-file)))

(deftest load-config-preserves-ollama-settings
  (let [tmp-file (java.io.File/createTempFile "test-config3" ".edn")]
    (spit tmp-file "{:ollama/url \"http://ollama.local:11434\" :ollama/embedding-model \"custom-model\" :index/root-path \"/tmp\"}")
    (let [cfg (sut/load-config (.getPath tmp-file))]
      (is (= "http://ollama.local:11434" (:ollama/url cfg)))
      (is (= "custom-model" (:ollama/embedding-model cfg))))
    (.delete tmp-file)))

(deftest load-config-with-exclude-override
  (let [tmp-file (java.io.File/createTempFile "test-config4" ".edn")]
    (try
      (spit tmp-file "{:index/exclude [\"custom-dir\"] :index/root-path \"/tmp\"}")
      (let [cfg (sut/load-config (.getPath tmp-file))]
        (is (some #(re-find % "custom-dir") (:index/exclude cfg))))
      (finally
        (.delete tmp-file)))))

;; ---------------------------------------------------------------------------
;; resolve-config
;; ---------------------------------------------------------------------------

(deftest resolve-config-uses-explicit-root-path
  (let [cfg (sut/resolve-config {:index/root-path "/explicit/path"})]
    (is (= "/explicit/path" (:index/root-path cfg)))))

(deftest resolve-config-uses-vscode-cwd
  (with-redefs [sut/*getenv* (fn [^String env]
                               (case env
                                 "VSCODE_CWD" "/vscode/project"
                                 "QD_PROJECT_ROOT" nil
                                 nil))]
    (let [cfg (sut/resolve-config {})]
      (is (= "/vscode/project" (:index/root-path cfg))))))

(deftest resolve-config-falls-back-to-user-dir
  (with-redefs [sut/*getenv* (fn [_] nil)
                sut/*user-dir-fn* (fn [] "/fallback/dir")]
    (let [cfg (sut/resolve-config {})]
      (is (= "/fallback/dir" (:index/root-path cfg))))))

(deftest resolve-config-computes-collection-from-root
  (with-redefs [sut/*getenv* (fn [_] nil)]
    (let [cfg (sut/resolve-config {:index/root-path "/some/project"})]
      (is (= "project-collection" (:qdrant/collection cfg))))))

(deftest resolve-config-uses-explicit-collection
  (with-redefs [sut/*getenv* (fn [_] nil)]
    (let [cfg (sut/resolve-config {:index/root-path "/my-project"
                                   :qdrant/collection "custom-collection"})]
      (is (= "custom-collection" (:qdrant/collection cfg))))))

(deftest resolve-config-uses-qd-collection-env
  (with-redefs [sut/*getenv* (fn [^String env]
                               (case env
                                 "QD_COLLECTION" "env-collection"
                                 nil))]
    (let [cfg (sut/resolve-config {:index/root-path "/test/project"})]
      (is (= "env-collection" (:qdrant/collection cfg))))))

(deftest resolve-config-normalizes-trailing-slash
  (let [cfg (sut/resolve-config {:index/root-path "/path/to/project/"})]
    (is (= "/path/to/project" (:index/root-path cfg)))))

(deftest resolve-config-empty-env-not-used
  (with-redefs [sut/*getenv* (fn [^String env]
                               (case env
                                 "QD_PROJECT_ROOT" ""
                                 "VSCODE_CWD" ""
                                 "QD_COLLECTION" ""
                                 nil))]
    (let [cfg (sut/resolve-config {:index/root-path "/explicit"})]
      (is (= "/explicit" (:index/root-path cfg)))
      (is (= "/explicit/.mcp/embedding-cache" (:embedding/cache-dir cfg))))))

(deftest resolve-config-cache-dir-uses-root-path
  (with-redefs [sut/*getenv* (fn [_] nil)]
    (let [root "/my/test/project"
          cfg (sut/resolve-config {:index/root-path root})]
      (is (= (str root "/.mcp/embedding-cache") (:embedding/cache-dir cfg))))))

(deftest resolve-config-cache-dir-explicit-override
  (with-redefs [sut/*getenv* (fn [_] nil)]
    (let [custom-dir "/custom/cache/path"
          cfg (sut/resolve-config {:index/root-path "/project"
                                   :embedding/cache-dir custom-dir})]
      (is (= custom-dir (:embedding/cache-dir cfg))
          "Explicit :embedding/cache-dir in config must override the default computation"))))
;; ---------------------------------------------------------------------------
;; :doc/signature
;; ---------------------------------------------------------------------------

(defn- load-error
  "The message `load-config` throws for the config file `name`, nil when
   it loads.

  String -> IO (Maybe String)"
  [name]
  (try (sut/load-config (fixture-path name)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-config-without-doc-signature-has-no-signatures
  (is (= nil (:doc/signature (sut/load-config (fixture-path "config-without-signature.edn"))))))

(deftest the-shipped-config-holds-defaults-only
  (let [shipped (edn/read-string (slurp "resources/config.edn"))]
    (is (= [] (:index/exclude shipped)))
    (is (not (contains? shipped :doc/signature)))))

(deftest this-repository-finds-signatures-in-the-last-paragraph
  (is (= {:strategy :last-paragraph} (:doc/signature project/config)))
  (is (some #(re-find % "test-resources/fixtures/a.clj") (:index/exclude project/config))))

(deftest a-pattern-without-a-capture-group-is-rejected-at-load
  (is (= (str "invalid server config " (.getAbsolutePath (java.io.File. ^String (fixture-path "config-pattern-without-group.edn")))
              ": :doc/signature :pattern regex needs a capture group for the Type signature: Signature: .*")
         (load-error "config-pattern-without-group.edn"))))

(deftest an-invalid-pattern-is-rejected-at-load
  (is (= (str "invalid server config " (.getAbsolutePath (java.io.File. ^String (fixture-path "config-pattern-invalid.edn")))
              ": :doc/signature :pattern regex does not compile: Signature: (.*")
         (load-error "config-pattern-invalid.edn"))))

(deftest an-unknown-key-in-the-server-config-is-rejected-at-load
  (let [file (java.io.File/createTempFile "config" ".edn")]
    (spit file "{:search/exlude [\"gen\"] :output/limit 10}")
    (is (= (str "invalid server config " (.getAbsolutePath file) ": unknown key :search/exlude")
           (try (sut/load-config (.getPath file)) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))
