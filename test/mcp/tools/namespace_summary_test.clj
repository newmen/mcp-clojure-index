(ns mcp.tools.namespace-summary-test
  "`namespace_summary` on this project's own code base."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn- answer
  "The answer text of `namespace_summary` on this project's code base.

  ToolArgs -> IO String
  Config -> ToolArgs -> IO String"
  ([args] (answer project/config args))
  ([cfg args]
   (project/answer-text cfg
                        (tool/call :namespace_summary args
                                   {:config cfg :code-index (project/code-index)}))))

(def ^:private output-definitions
  "Hand-checked Definitions of `src/mcp/output.clj`.

  [String]"
  ["12  public  s/def  :mcp.output/header"
   "14  public  s/def  :mcp.output/footer"
   "16  public  s/def  :mcp.output/answer-frame"
   "22  public  s/def  :mcp.output/output-budget"
   "24  public  defrecord  ->OutputBudget  [limit max-chars offset]"
   "24  public  defrecord  OutputBudget"
   "24  public  defrecord  map->OutputBudget  [m]"
   "30  public  defn  budget  [cfg {:keys [limit offset]}]  The Output budget of a call with the tool's `args`."
   "43  private  defn-  truncation-note  [shown-until total]  The Truncation note of a page."
   "57  public  defn  relative-path  [root-path file-path]  Returns `file-path` relative to the project root `root-path`; paths"
   "72  public  defn  first-line  [text]  The first non-blank line of `text`, trimmed; nil if none."
   "86  private  defn-  answer-text  [shown offset total]  Joins the `shown` findings of a page starting at `offset`, adding a"
   "97  private  defn-  oversized-first-finding  [max-chars page offset total]  Cuts the first finding of a page that alone exceeds `max-chars`, so"
   "110  public  defn  render  [{:keys [limit max-chars offset]} findings]  Renders `findings` (one string per finding) as an answer within the"
   "123  public  defn  render-framed  [budget {:keys [header footer]} findings]  Renders `findings` inside an AnswerFrame."
   "142  public  defn  checked  [index] [index include-tests?]  What an answer searched: `(checked N files, including tests)`, or"
   "156  public  defn  not-found  [index name-str]  The answer for a name that resolves to nothing."
   "165  public  defn  definition->line  [index {:keys [filename row kind arglists doc] :as definition}]  The Compact answer line of a Definition."
   "180  public  defn  ambiguous-header  [what name-str candidate-count]  The header line for `name-str` that denotes `candidate-count`"
   "188  public  defn  ambiguous  [budget index what name-str definitions]  The answer for `name-str` that denotes several `definitions`: an"
   "198  public  defn  one-definition  [budget index name-str definitions answer-fn]  The answer for `name-str` that resolved to `definitions`: `not-found`"])

(def ^:private output-requirers
  "Hand-checked namespaces that require `mcp.output` (more may be added).

  #{String}"
  #{"mcp.output-test" "mcp.tools.semantic-search" "mcp.tools.find-definition"})

(defn- required-by
  "The namespace names of the `required by:` line of an answer, or nil.

  String -> Maybe #{String}"
  [text]
  (some->> (st/split-lines text)
           (some #(second (re-matches #"required by: (.*)" %)))
           (#(st/split % #", "))
           set))

(def ^:private roomy-config
  "The config of this project with room for every Definition of
   `src/mcp/output.clj` in one answer.

  Config"
  (assoc project/config :output/max-chars 10000))

(deftest summarises-every-definition-of-a-namespace
  (let [[header & lines] (st/split-lines (answer roomy-config {:ns "mcp.output"}))]
    (is (= "ns mcp.output  src/mcp/output.clj  Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
           header))
    (is (= output-definitions (butlast lines)))))

(deftest lists-namespaces-that-require-it
  (let [text (answer roomy-config {:ns "mcp.output"})
        requirers (required-by text)]
    (is (st/starts-with? (last (st/split-lines text)) "required by: "))
    (is (every? requirers output-requirers))
    (is (not (contains? requirers "mcp.output")))))

(deftest namespace-nobody-requires
  (is (st/ends-with? (answer {:ns "mcp.output-test"}) "required by: none")))

(deftest unknown-namespace-returns-close-candidates
  (testing "misspelt"
    (testing "says what was searched, then the close names"
      (is (re-matches #"not found: namespace mcp\.outptu \(checked \d+ files, including tests\); close: .*mcp\.output.*"
                      (answer {:ns "mcp.outptu"})))))
  (testing "partial name"
    (is (st/includes? (answer {:ns "find-definition"}) "mcp.tools.find-definition")))
  (testing "nothing close"
    (is (re-matches #"not found: namespace zzzzzzzz \(checked \d+ files, including tests\)"
                    (answer {:ns "zzzzzzzz"})))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 3)
        text (answer cfg {:ns "mcp.output"})
        lines (st/split-lines text)]
    (is (= 5 (count lines)))
    (is (= (take 3 output-definitions) (take 3 (rest lines))))
    (is (re-matches #"truncated: shown 3 of \d+, pass offset=3 or narrow the query" (last lines)))
    (is (= (drop 3 (take 6 output-definitions))
           (take 3 (rest (st/split-lines (answer cfg {:ns "mcp.output" :offset 3}))))))
    (is (<= (count text) (:output/max-chars cfg))))
  (testing "character ceiling"
    (let [cfg (assoc project/config :output/max-chars 300)
          text (answer cfg {:ns "mcp.output"})]
      (is (<= (count text) 300))
      (is (re-find #"truncated: shown \d+ of \d+" text))))
  (testing "default budget"
    (is (<= (count (answer {:ns "mcp.code-index"})) (:output/max-chars project/config)))))

(def ^:private definitions-fixture-config
  (assoc project/config :index/root-path
         (.getCanonicalPath (File. "test-resources/fixtures/definitions_project"))))

(deftest defmethods-belong-to-the-namespace-of-their-file
  (testing "a tool namespace lists its own defmethods with their dispatch values"
    (let [lines (st/split-lines (answer {:ns "mcp.tools.get-source"}))]
      (is (some (partial re-matches #"\d+  public  defmethod  mcp\.tool/spec :get_source") lines))
      (is (some (partial re-matches #"\d+  public  defmethod  mcp\.tool/call :get_source") lines))))
  (testing "the multimethod's namespace does not list defmethods written in other files"
    (is (not (st/includes? (answer {:ns "mcp.tool"}) "defmethod"))))
  (testing "a defmethod of a local multimethod is named without its namespace"
    (let [text (-> (tool/call :namespace_summary {:ns "app.core"}
                              {:config definitions-fixture-config
                               :code-index (code-index/build definitions-fixture-config)})
                   :content first :text)]
      (is (some #{"15  public  defmethod  area :circle"} (st/split-lines text))))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "namespace_summary" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"grep" (:description spec)))
    (is (= #{:ns :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))
    (is (= ["ns"] (get-in spec [:inputSchema :required])))))
