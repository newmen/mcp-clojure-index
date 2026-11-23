(ns mcp.tools.find-definition-test
  "Acceptance case 4 of the spec, on this project's own code base."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn- answer
  "The answer text of `find_definition` on this project's code base.

  ToolArgs -> IO String
  Config -> ToolArgs -> IO String"
  ([args] (answer project/config args))
  ([cfg args]
   (project/answer-text cfg
                        (tool/call :find_definition args
                                   {:config cfg :code-index (project/code-index)}))))

(def ^:private relative-path-line
  (str "src/mcp/output.clj:43  defn  mcp.output/relative-path  [root-path file-path]  "
       "Returns `file-path` relative to the project root `root-path`; paths"))

(deftest finds-definition-by-simple-alias-qualified-and-fully-qualified-name
  (testing "simple"
    (is (= relative-path-line (answer {:name "relative-path"}))))
  (testing "alias-qualified, as written in mcp.tools"
    (is (= relative-path-line (answer {:name "output/relative-path"}))))
  (testing "fully qualified"
    (is (= relative-path-line (answer {:name "mcp.output/relative-path"})))))

(deftest finds-namespace-definition
  (is (= "src/mcp/output.clj:1  ns  mcp.output  Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
         (answer {:name "mcp.output"}))))

(deftest ambiguous-simple-name-returns-candidates
  (let [[header & candidates] (st/split-lines (answer {:name "test-config"}))]
    (is (= "ambiguous: 3 definitions named test-config, pass a qualified name" header))
    (is (= [["test/mcp/qdrant_test.clj" "mcp.qdrant-test/test-config"]
            ["test/mcp/search_test.clj" "mcp.search-test/test-config"]
            ["test/mcp/tools_test.clj" "mcp.tools-test/test-config"]]
           (map #(rest (re-matches #"(\S+):\d+  def  (\S+)" %)) candidates)))))

(deftest miss-reports-what-was-searched
  (is (= (str "not found: no-such-definition (checked "
              (count (code-index/files (project/code-index)))
              " files, including tests)")
         (answer {:name "no-such-definition"}))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 2)
        text (answer cfg {:name "test-config"})]
    (is (= 4 (count (st/split-lines text))))
    (is (st/ends-with? text "truncated: shown 2 of 3, pass offset=2 or narrow the query"))
    (is (st/includes? (answer cfg {:name "test-config" :offset 2}) "mcp.tools-test/test-config"))
    (is (<= (count text) (:output/max-chars cfg)))))

(def ^:private definitions-fixture
  (delay (code-index/build
          (assoc project/config :index/root-path
                 (.getCanonicalPath (File. "test-resources/fixtures/definitions_project"))))))

(defn- fixture-answer
  "The answer text of `find_definition` on the definitions fixture.

  ToolArgs -> IO String"
  [args]
  (project/answer-text project/config
                       (tool/call :find_definition args
                                  {:config project/config :code-index @definitions-fixture})))

(deftest finds-tests-methods-specs-and-project-macro-definitions
  (testing "deftest"
    (is (= "test/app/core_test.clj:5  deftest  app.core-test/helper-test  []"
           (fixture-answer {:name "helper-test"}))))
  (testing "defmethod with its dispatch value"
    (is (= "src/app/core.clj:15  defmethod  app.core/area :circle"
           (fixture-answer {:name "area :circle"}))))
  (testing "s/def by keyword"
    (is (= "src/app/core.clj:23  s/def  :app.core/uuid"
           (fixture-answer {:name ":app.core/uuid"}))))
  (testing "form of a project macro declared via lint-as"
    (is (= "src/app/core.clj:26  defhandler  app.core/handle-uuid  [req]  Handles a uuid."
           (fixture-answer {:name "handle-uuid"})))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "find_definition" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"alias" (:description spec)))
    (is (re-find #"grep" (:description spec)))
    (is (re-find #"area :circle" (:description spec)))
    (is (re-find #"::uuid" (:description spec)))
    (is (= #{:name :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))))

(deftest old-lookup-tools-are-gone
  (let [names (set (map :name (tools/tool-definitions)))]
    (is (contains? names "find_definition"))
    (is (not (contains? names "find_symbol")))
    (is (not (contains? names "find_namespace")))))
