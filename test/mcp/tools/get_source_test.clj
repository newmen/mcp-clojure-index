(ns mcp.tools.get-source-test
  "`get_source` on this project's own code base."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools]))

(set! *warn-on-reflection* true)

(defn- answer
  "The answer text of `get_source` on this project's code base.

  ToolArgs -> IO String
  Config -> ToolArgs -> IO String"
  ([args] (answer project/config args))
  ([cfg args]
   (project/answer-text cfg
                        (tool/call :get_source args
                                   {:config cfg :code-index (project/code-index)}))))

(def ^:private first-line-source
  (str "src/mcp/output.clj:52-60\n"
       "(defn first-line\n"
       "  \"The first non-blank line of `text`, trimmed; nil if none.\n"
       "\n"
       "  Maybe String -> Maybe String\"\n"
       "  [text]\n"
       "  (some->> (some-> text st/split-lines)\n"
       "           (map st/trim)\n"
       "           (remove st/blank?)\n"
       "           first))"))

(deftest returns-source-of-one-definition-with-line-range-header
  (testing "simple name"
    (is (= first-line-source (answer {:name "first-line"}))))
  (testing "fully qualified name"
    (is (= first-line-source (answer {:name "mcp.output/first-line"})))))

(deftest alias-qualified-name-resolves-like-find-definition
  (is (= first-line-source (answer {:name "output/first-line"}))))

(deftest ambiguous-name-returns-candidates-not-bodies
  (let [[header & candidates] (st/split-lines (answer {:name "test-config"}))]
    (is (= "ambiguous: 3 definitions named test-config, pass a qualified name" header))
    (is (= [["test/mcp/qdrant_test.clj" "mcp.qdrant-test/test-config"]
            ["test/mcp/search_test.clj" "mcp.search-test/test-config"]
            ["test/mcp/tools_test.clj" "mcp.tools-test/test-config"]]
           (map #(rest (re-matches #"(\S+):\d+  def  (\S+)" %)) candidates)))))

(deftest miss-reports-what-was-searched
  (is (re-matches #"not found: no-such-definition \(checked \d+ files, including tests\)"
                  (answer {:name "no-such-definition"}))))

(deftest body-over-budget-ends-with-truncation-note-and-pages-by-line
  (let [cfg (assoc project/config :output/max-chars 100)
        text (answer cfg {:name "mcp.output/first-line"})]
    (is (= (str "src/mcp/output.clj:52-60\n"
                "(defn first-line\n"
                "truncated: shown 1 of 9, pass offset=1 or narrow the query")
           text))
    (is (<= (count text) 100)))
  (testing "offset skips lines of the body"
    (is (= (str "src/mcp/output.clj:52-60\n"
                "           (remove st/blank?)\n"
                "           first))")
           (answer {:name "mcp.output/first-line" :offset 7}))))
  (testing "limit lowers the number of lines"
    (is (= (str "src/mcp/output.clj:52-60\n"
                "(defn first-line\n"
                "  \"The first non-blank line of `text`, trimmed; nil if none.\n"
                "truncated: shown 2 of 9, pass offset=2 or narrow the query")
           (answer {:name "mcp.output/first-line" :limit 2})))))

(deftest ambiguous-candidates-obey-limit-and-offset
  (let [lines (st/split-lines (answer {:name "test-config" :limit 1 :offset 1}))]
    (is (= 3 (count lines)))
    (is (re-matches #"test/mcp/search_test.clj:\d+  def  mcp.search-test/test-config" (second lines)))
    (is (= "truncated: shown 2 of 3, pass offset=2 or narrow the query" (last lines)))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "get_source" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"grep" (:description spec)))
    (is (re-find #"ambiguous" (:description spec)))
    (is (= #{:name :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))
    (is (= ["name"] (get-in spec [:inputSchema :required])))))
