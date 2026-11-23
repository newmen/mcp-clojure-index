(ns mcp.tools.get-source-test
  "`get_source` on this project's own code base."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io File)))

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

(def ^:private relative-path-source
  (str "src/mcp/output.clj:63-72\n"
       "(defn relative-path\n"
       "  \"Returns `file-path` relative to the project root `root-path`; paths\n"
       "   outside the root are returned unchanged.\n"
       "\n"
       "  Maybe String -> String -> String\"\n"
       "  [root-path file-path]\n"
       "  (let [prefix (str (st/replace (or root-path \"\") #\"/+$\" \"\") \"/\")]\n"
       "    (if (st/starts-with? (str file-path) prefix)\n"
       "      (subs file-path (count prefix))\n"
       "      file-path)))"))

(deftest returns-source-of-one-definition-with-line-range-header
  (testing "simple name"
    (is (= relative-path-source (answer {:name "relative-path"}))))
  (testing "fully qualified name"
    (is (= relative-path-source (answer {:name "mcp.output/relative-path"})))))

(deftest alias-qualified-name-resolves-like-find-definition
  (is (= relative-path-source (answer {:name "output/relative-path"}))))

(deftest ambiguous-name-returns-candidates-not-bodies
  (let [[header & candidates] (st/split-lines (answer {:name "test-config"}))]
    (is (= "ambiguous: 3 definitions named test-config, pass a qualified name" header))
    (is (= [["test/mcp/qdrant_test.clj" "mcp.qdrant-test/test-config"]
            ["test/mcp/search_test.clj" "mcp.search-test/test-config"]
            ["test/mcp/tools_test.clj" "mcp.tools-test/test-config"]]
           (map #(rest (re-matches #"(\S+):\d+  def  (\S+)" %)) candidates)))))

(deftest ambiguous-candidates-show-their-type-signatures
  (let [cfg (assoc project/config :index/root-path
                   (.getCanonicalPath (File. "test-resources/fixtures/definitions_project")))]
    (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
                "src/app/signatures.clj:12  defn  app.signatures/relative-path  [file-path]  "
                "String -> String  Путь к файлу относительно корня.\n"
                "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
                "Maybe String -> String -> String  "
                "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
                "(+38 chars)")
           (project/answer-text cfg
                                (tool/call :get_source {:name "relative-path"}
                                           {:config cfg :code-index (code-index/build cfg)}))))))

(deftest miss-reports-what-was-searched
  (is (re-matches #"not found: no-such-definition \(checked \d+ files, including tests\)"
                  (answer {:name "no-such-definition"}))))

(deftest body-over-budget-ends-with-truncation-note-and-pages-by-line
  (let [cfg (assoc project/config :output/max-chars 110)
        text (answer cfg {:name "mcp.output/relative-path"})]
    (is (= (str "src/mcp/output.clj:63-72\n"
                "(defn relative-path\n"
                "truncated: shown 1 of 10, pass offset=1 or narrow the query")
           text))
    (is (<= (count text) 110)))
  (testing "offset skips lines of the body"
    (is (= (str "src/mcp/output.clj:63-72\n"
                "      (subs file-path (count prefix))\n"
                "      file-path)))")
           (answer {:name "mcp.output/relative-path" :offset 8}))))
  (testing "limit lowers the number of lines"
    (is (= (str "src/mcp/output.clj:63-72\n"
                "(defn relative-path\n"
                "  \"Returns `file-path` relative to the project root `root-path`; paths\n"
                "truncated: shown 2 of 10, pass offset=2 or narrow the query")
           (answer {:name "mcp.output/relative-path" :limit 2})))))

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

(deftest source-line-longer-than-the-budget-ends-with-the-source-truncation-note
  (let [cfg (assoc project/config
                   :output/max-chars 200
                   :index/root-path (.getCanonicalPath (File. "test-resources/fixtures/definitions_project")))
        long-line (str "  [" (st/join " " (take 60 (for [a "ABC" b "ABCDEFGHIJKLMNOPQRSTUVWXYZ"] (str \" a b \"))))
                       "])")]
    (is (= (str "src/app/long_lines.clj:4-6\n"
                (subs long-line 0 132) "\n"
                "truncated: source shown 132 of 304 chars")
           (project/answer-text cfg
                                (tool/call :get_source {:name "app.long-lines/country-codes" :offset 2}
                                           {:config cfg :code-index (code-index/build cfg)}))))))
