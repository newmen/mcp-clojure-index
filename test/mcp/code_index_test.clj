(ns mcp.code-index-test
  "The precise index on this project's own code base (no Qdrant/Ollama)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as sut]
            [mcp.config :as config]
            [mcp.test-project :as project])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- located
  [defs]
  (set (map (juxt sut/qualified-name :row) defs)))

(deftest resolves-simple-alias-qualified-and-fully-qualified-names
  (let [idx (project/code-index)
        expected #{["mcp.output/relative-path" 25]}]
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

(deftest does-not-index-agent-worktrees-under-dot-claude
  (is (not-any? #(re-find #"/\\.claude/" (subs % (count project/root)))
                (sut/files (project/code-index)))))

;; ---------------------------------------------------------------------------
;; Freshness (ADR-0005)
;; ---------------------------------------------------------------------------

(defn- temp-project
  "A fresh project directory with `files` ({relative-path content})."
  [files]
  (let [root (.toFile (Files/createTempDirectory "code-index-test" (make-array FileAttribute 0)))]
    (doseq [[relative-path content] files]
      (io/make-parents (io/file root relative-path))
      (spit (io/file root relative-path) content))
    (.getCanonicalPath root)))

(defn- temp-config
  [root]
  (assoc config/defaults :index/root-path root))

(defn- write-later!
  "Write `content` to `file-path` with an mtime visibly later than before."
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
