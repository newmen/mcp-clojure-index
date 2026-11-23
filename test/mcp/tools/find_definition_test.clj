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
  (str "src/mcp/output.clj:63  defn  mcp.output/relative-path  [root-path file-path]  "
       "Maybe String -> String -> String  "
       "Returns `file-path` relative to the project root `root-path`; paths outside the root "
       "are returned unchanged."))

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

  ToolArgs -> IO String
  Config -> ToolArgs -> IO String"
  ([args] (fixture-answer project/config args))
  ([cfg args]
   (project/answer-text cfg
                        (tool/call :find_definition args
                                   {:config cfg :code-index @definitions-fixture}))))

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

(def ^:private summary-answers
  "The `find_definition` answers on the Docstring summary fixtures, by name.

  {String String}"
  {"app.summaries/relative-path"
   (str "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
        "Maybe String -> String -> String  "
        "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
        "(+38 chars)")
   "app.summaries/limit"
   "src/app/summaries.clj:13  def  app.summaries/limit  Lines per page."
   "app.summaries/cache!"
   "src/app/summaries.clj:17  defn  app.summaries/cache!  [x]  Any -> IO ()  Stores x, e.g. (+32 chars)"
   "app.summaries/clear!"
   (str "src/app/summaries.clj:24  defn  app.summaries/clear!  []  IO ()  "
        "Calls `cache!` and `set-cached-tc!` with nil.")})

(deftest definition-line-shows-the-docstring-summary
  (testing "a Russian docstring wrapped over several lines gives its whole first sentence"
    (is (= (summary-answers "app.summaries/relative-path")
           (fixture-answer {:name "app.summaries/relative-path"}))))
  (testing "a full stop right before the closing quote ends the summary, nothing remains"
    (is (= (summary-answers "app.summaries/limit")
           (fixture-answer {:name "app.summaries/limit"}))))
  (testing "`e.g.` cuts the sentence; a backticked `foo!` does not"
    (is (= (summary-answers "app.summaries/cache!")
           (fixture-answer {:name "app.summaries/cache!"})))
    (is (= (summary-answers "app.summaries/clear!")
           (fixture-answer {:name "app.summaries/clear!"}))))
  (testing "every answer stays within the Output budget"
    (is (every? #(<= (count (fixture-answer {:name %})) (:output/max-chars project/config))
                (keys summary-answers)))))

(deftest definition-line-shows-the-type-signature-after-the-arglists
  (testing "a `def` whose signature is a bare type"
    (is (= "src/app/signatures.clj:6  def  app.signatures/page-size  Long  Rows per page."
           (fixture-answer {:name "app.signatures/page-size"}))))
  (testing "a namespace has no signature, its second paragraph stays in the summary"
    (is (= (str "src/app/signatures.clj:1  ns  app.signatures  Type signature fixtures. "
                "(+64 chars)")
           (fixture-answer {:name "app.signatures"}))))
  (testing "ambiguous candidates show their signatures"
    (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
                "src/app/signatures.clj:12  defn  app.signatures/relative-path  [file-path]  "
                "String -> String  Путь к файлу относительно корня.\n"
                (summary-answers "app.summaries/relative-path"))
           (fixture-answer {:name "relative-path"}))))
  (testing "this code base's own docstrings"
    (is (= relative-path-line (answer {:name "mcp.output/relative-path"})))))

(deftest signature-strategy-comes-from-the-config
  (testing "`:pattern` takes the first group of the regex"
    (is (= (str "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
                "Maybe String -> String  "
                "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
                "(+38 chars)")
           (fixture-answer (assoc project/config :doc/signature
                                  {:strategy :pattern :regex "(Maybe String -> String) -> String"})
                           {:name "app.summaries/relative-path"}))))
  (testing "a config without `:doc/signature` shows no signatures"
    (is (= (str "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
                "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
                "(+71 chars)")
           (fixture-answer (dissoc project/config :doc/signature)
                           {:name "app.summaries/relative-path"})))
    (is (= "src/app/signatures.clj:6  def  app.signatures/page-size  Rows per page. (+4 chars)"
           (fixture-answer (assoc project/config :doc/signature nil)
                           {:name "app.signatures/page-size"})))))
