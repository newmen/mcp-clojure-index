(ns mcp.code-index-test
  "The precise index on this project's own code base (no Qdrant/Ollama)."
  (:require [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as sut]
            [mcp.test-project :as project]))

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
