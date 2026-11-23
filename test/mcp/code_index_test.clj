(ns mcp.code-index-test
  "The precise index on this project's own code base (no Qdrant/Ollama)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as sut]
            [mcp.config :as config]
            [mcp.test-project :as project])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- located
  "The qualified name and row of each of `defs`.

  [Definition] -> #{[String Long]}"
  [defs]
  (set (map (juxt sut/qualified-name :row) defs)))

(deftest resolves-simple-alias-qualified-and-fully-qualified-names
  (let [idx (project/code-index)
        expected #{["mcp.output/relative-path" 43]}]
    (testing "simple name"
      (is (= expected (located (sut/resolve-name idx "relative-path")))))
    (testing "alias-qualified name, as written in mcp.tools"
      (is (= expected (located (sut/resolve-name idx "output/relative-path")))))
    (testing "fully qualified name"
      (is (= expected (located (sut/resolve-name idx "mcp.output/relative-path")))))))

(deftest resolves-a-namespace-name-to-its-ns-form
  (is (= #{["mcp.output" 1]}
         (located (sut/resolve-name (project/code-index) "mcp.output")))))

(deftest ambiguous-simple-name-resolves-to-every-candidate
  (is (= #{"mcp.qdrant-test/test-config"
           "mcp.search-test/test-config"
           "mcp.tools-test/test-config"}
         (set (map sut/qualified-name
                   (sut/resolve-name (project/code-index) "test-config"))))))

(deftest unknown-names-resolve-to-nothing
  (let [idx (project/code-index)]
    (is (empty? (sut/resolve-name idx "no-such-definition")))
    (is (empty? (sut/resolve-name idx "output/no-such-definition")))
    (is (empty? (sut/resolve-name idx "no.such.ns/relative-path")))
    (is (empty? (sut/resolve-name idx "")))))

;; ---------------------------------------------------------------------------
;; Definitions from tests, multimethods, specs and project macros
;; ---------------------------------------------------------------------------

(def ^:private fixture-root
  (.getCanonicalPath (File. "test-resources/fixtures/definitions_project")))

(def ^:private fixture-index
  (delay (sut/build (assoc project/config :index/root-path fixture-root))))

(defn- described
  "The qualified name, kind and row of each of `defs`.

  [Definition] -> #{[String String Long]}"
  [defs]
  (set (map (juxt sut/qualified-name :kind :row) defs)))

(deftest deftest-defmethod-spec-and-lint-as-macro-yield-definitions
  (let [defs (described (sut/definitions @fixture-index))]
    (is (contains? defs ["app.core-test/helper-test" "deftest" 5]))
    (is (contains? defs ["app.core/area" "defmulti" 11]))
    (is (contains? defs ["app.core/area :circle" "defmethod" 15]))
    (is (contains? defs ["app.core/area [:sq :rect]" "defmethod" 19]))
    (is (contains? defs [":app.core/uuid" "s/def" 23]))
    (is (contains? defs ["app.core/handle-uuid" "defhandler" 26]))))

(deftest defmethod-and-spec-definitions-span-their-whole-form
  (let [by-name (into {} (map (juxt sut/qualified-name identity))
                      (sut/definitions @fixture-index))]
    (is (= [15 1 17 22] ((juxt :row :col :end-row :end-col) (by-name "app.core/area :circle"))))
    (is (= [23 1 24 39] ((juxt :row :col :end-row :end-col) (by-name ":app.core/uuid"))))))

(deftest resolves-test-method-spec-and-macro-names
  (let [idx @fixture-index
        resolved #(described (sut/resolve-name idx %))]
    (is (= #{["app.core-test/helper-test" "deftest" 5]} (resolved "helper-test")))
    (is (= #{["app.core/handle-uuid" "defhandler" 26]} (resolved "handle-uuid")))
    (testing "a multimethod name denotes the defmulti, not its methods"
      (is (= #{["app.core/area" "defmulti" 11]} (resolved "area"))))
    (testing "a multimethod name with a dispatch value denotes that method"
      (is (= #{["app.core/area :circle" "defmethod" 15]} (resolved "area :circle")))
      (is (= #{["app.core/area [:sq :rect]" "defmethod" 19]} (resolved "core/area [:sq :rect]")))
      (is (= #{["app.core/area :circle" "defmethod" 15]} (resolved "app.core/area :circle"))))
    (testing "a spec keyword, fully qualified, auto-resolved or alias-qualified"
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved ":app.core/uuid")))
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved "::uuid")))
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved "::core/uuid")))
      (is (empty? (resolved ":other/uuid"))))))

(deftest defmethod-written-in-another-namespace-keeps-its-multimethod-name
  (let [idx (project/code-index)
        expected #{["mcp.tool/call :get_source" (str project/root "/src/mcp/tools/get_source.clj")]}
        resolved #(set (map (juxt sut/qualified-name :filename) (sut/resolve-name idx %)))]
    (is (= expected (resolved "mcp.tool/call :get_source")))
    (is (= expected (resolved "tool/call :get_source")))
    (is (= #{'mcp.tools.get-source}
           (set (map :ns (sut/resolve-name idx "mcp.tool/call :get_source")))))))

(deftest enclosing-definition-of-usages-inside-these-forms
  (let [idx @fixture-index
        enclosing (fn [{:keys [filename name-row name-col]}]
                    (some-> (sut/enclosing-definition idx filename name-row name-col)
                            sut/qualified-name))]
    (is (= #{[17 "app.core/area :circle"]
             [24 ":app.core/uuid"]
             [29 "app.core/handle-uuid"]
             [33 "app.core/Circle"]
             [6 "app.core-test/helper-test"]}
           (->> (sut/entries idx :var-usages)
                (filter (comp #{'helper} :name))
                (filter :name-row)
                (map (juxt :name-row enclosing))
                set)))
    (testing "a position outside every Definition has none"
      (is (nil? (sut/enclosing-definition idx (str fixture-root "/src/app/core.clj") 5 1))))))

(deftest project-kondo-config-is-optional
  (let [idx (sut/build (assoc project/config :index/root-path
                              (.getCanonicalPath (File. "test-resources/fixtures/multi_ns"))))]
    (is (seq (sut/definitions idx)))))

(deftest indexes-source-and-test-files-but-not-excluded-ones
  (let [idx (project/code-index)
        files (set (map #(subs % (inc (count project/root))) (sut/files idx)))]
    (is (contains? files "src/mcp/output.clj"))
    (is (contains? files "test/mcp/output_test.clj"))
    (is (contains? files "project.clj"))
    (is (contains? files "resources/config.edn"))
    (is (not-any? #(re-find #"^test-resources/" %) files))
    (is (sut/test-file? idx (str project/root "/test/mcp/output_test.clj")))
    (is (not (sut/test-file? idx (str project/root "/src/mcp/output.clj"))))))

(deftest test-files-are-under-a-test-path-or-named-like-tests-only-in-the-fallback
  (let [usages-root (.getCanonicalPath (File. "test-resources/usages"))
        build #(sut/build (assoc project/config :index/root-path (str usages-root "/" %)))]
    (testing "declared Test paths (project.clj :test-paths [\"spec\"])"
      (let [idx (build "lein_project")]
        (is (sut/test-file? idx (str usages-root "/lein_project/spec/app/core_spec.clj")))
        (is (not (sut/test-file? idx (str usages-root "/lein_project/src/app/core_test.clj"))))))
    (testing "fallback: test/ and *_test files"
      (let [idx (build "plain_project")]
        (is (sut/test-file? idx (str usages-root "/plain_project/test/plain/b.clj")))
        (is (sut/test-file? idx (str usages-root "/plain_project/src/plain/c_test.clj")))
        (is (not (sut/test-file? idx (str usages-root "/plain_project/src/plain/a.clj"))))))))

(deftest does-not-index-agent-worktrees-under-dot-claude
  (is (not-any? #(re-find #"/\\.claude/" (subs % (count project/root)))
                (sut/files (project/code-index)))))

;; ---------------------------------------------------------------------------
;; Freshness (ADR-0005)
;; ---------------------------------------------------------------------------

(defn- temp-project
  "Creates a fresh project directory with `files` ({relative-path content})
   and returns its canonical path.

  {String String} -> IO String"
  [files]
  (let [root (.toFile (Files/createTempDirectory "code-index-test" (make-array FileAttribute 0)))]
    (doseq [[relative-path content] files]
      (io/make-parents (io/file root relative-path))
      (spit (io/file root relative-path) content))
    (.getCanonicalPath root)))

(deftest project-clj-without-test-paths-falls-back-to-test-dir-and-test-names
  (let [root (temp-project {"project.clj" "(defproject app \"0.1.0\" :dependencies [])"
                            "src/app/core.clj" "(ns app.core)"
                            "src/app/core_test.clj" "(ns app.core-test)"
                            "test/app/helpers.clj" "(ns app.helpers)"})
        idx (sut/build (assoc project/config :index/root-path root))]
    (is (sut/test-file? idx (str root "/test/app/helpers.clj")))
    (is (sut/test-file? idx (str root "/src/app/core_test.clj")))
    (is (not (sut/test-file? idx (str root "/src/app/core.clj"))))))

(defn- temp-config
  "The default config for the project at `root`.

  String -> Config"
  [root]
  (assoc config/defaults :index/root-path root))

(defn- write-later!
  "Writes `content` to `file-path` with an mtime visibly later than before.

  String -> String -> IO Boolean"
  [^String file-path content]
  (let [file (io/file file-path)
        before (.lastModified file)]
    (spit file content)
    (.setLastModified file (+ 2000 (max before (System/currentTimeMillis))))))

(def ^:private core-v1 "(ns app.core)\n(defn alpha [x] x)\n")
(def ^:private util-src "(ns app.util)\n(defn helper [] 1)\n")

(deftest refresh-reflects-edit-add-and-delete
  (let [root (temp-project {"src/app/core.clj" core-v1
                            "src/app/util.clj" util-src})
        cfg (temp-config root)
        idx (sut/build cfg)]
    (testing "edit"
      (write-later! (str root "/src/app/core.clj") "(ns app.core)\n\n(defn beta [y] y)\n")
      (let [fresh (sut/refresh cfg idx)]
        (is (empty? (sut/resolve-name fresh "alpha")))
        (is (= #{["app.core/beta" 3]} (located (sut/resolve-name fresh "beta"))))))
    (testing "add"
      (spit (io/file root "src/app/extra.clj") "(ns app.extra)\n(def gamma 1)\n")
      (is (= #{["app.extra/gamma" 2]}
             (located (sut/resolve-name (sut/refresh cfg idx) "gamma")))))
    (testing "delete"
      (io/delete-file (io/file root "src/app/util.clj"))
      (let [fresh (sut/refresh cfg idx)]
        (is (empty? (sut/resolve-name fresh "helper")))
        (is (not-any? #{(str root "/src/app/util.clj")} (sut/files fresh)))))))

(deftest stale-files-are-only-the-changed-added-and-deleted-ones
  (let [root (temp-project {"src/app/core.clj" core-v1
                            "src/app/util.clj" util-src
                            "src/app/gone.clj" "(ns app.gone)\n"})
        cfg (temp-config root)
        idx (sut/build cfg)]
    (is (empty? (sut/stale-files cfg idx)))
    (write-later! (str root "/src/app/core.clj") "(ns app.core)\n(defn beta [] 2)\n")
    (spit (io/file root "src/app/added.clj") "(ns app.added)\n")
    (io/delete-file (io/file root "src/app/gone.clj"))
    (is (= #{(str root "/src/app/core.clj")
             (str root "/src/app/added.clj")
             (str root "/src/app/gone.clj")}
           (set (sut/stale-files cfg idx))))))

(deftest refresh-of-an-unchanged-project-returns-the-same-index
  (let [idx (project/code-index)]
    (is (identical? idx (sut/refresh project/config idx)))))

(deftest freshness-check-on-this-code-base-takes-negligible-time
  (let [idx (project/code-index)
        _warm-up (sut/refresh project/config idx)
        runs 5
        start (System/nanoTime)
        _ (dotimes [_run runs] (sut/refresh project/config idx))
        elapsed-ms (/ (- (System/nanoTime) start) 1e6 runs)]
    (is (< elapsed-ms 50)
        (str "freshness check took " elapsed-ms "ms on " (count (sut/files idx)) " files"))))
