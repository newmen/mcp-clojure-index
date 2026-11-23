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
   "26  private  def  sentence-marks  The characters that end a sentence of a Docstring summary. (+12 chars)"
   "36  public  defn  budget  [cfg {:keys [limit offset]}]  The Output budget of a call with the tool's `args`. (+135 chars)"
   "49  private  defn-  truncation-note  [shown-until total]  The Truncation note of a page. (+73 chars)"
   "63  public  defn  relative-path  [root-path file-path]  Returns `file-path` relative to the project root `root-path`; paths outside the root are returned unchanged. (+32 chars)"
   "78  private  defn-  sentence-end  [text]  The index just past the first sentence of the whitespace-collapsed `text`; nil when the whole text is one sentence. (+126 chars)"
   "100  public  defn  docstring-summary  [doc]  The Docstring summary of `doc`: its first sentence with the source line breaks joined; nil when the docstring is blank. (+164 chars)"
   "120  private  defn-  answer-text  [shown offset total]  Joins the `shown` findings of a page starting at `offset`, adding a Truncation note when findings remain after them. (+34 chars)"
   "131  private  defn-  oversized-first-finding  [max-chars page offset total]  Cuts the first finding of a page that alone exceeds `max-chars`, so that paging still advances by one. (+42 chars)"
   "144  public  defn  render  [{:keys [limit max-chars offset]} findings]  Renders `findings` (one string per finding) as an answer within the Output budget. (+34 chars)"
   "157  public  defn  render-framed  [budget {:keys [header footer]} findings]  Renders `findings` inside an AnswerFrame. (+163 chars)"
   "176  public  defn  checked  [index] [index include-tests?]  What an answer searched: `(checked N files, including tests)`, or excluding tests (which are then not counted) when `include-tests?` is false. (+50 chars)"
   "190  public  defn  not-found  [index name-str]  The answer for a name that resolves to nothing. (+56 chars)"
   "199  public  defn  definition->line  [index {:keys [filename row kind arglists doc] :as definition}]  The Compact answer line of a Definition. (+100 chars)"
   "214  public  defn  ambiguous-header  [what name-str candidate-count]  The header line for `name-str` that denotes `candidate-count` candidates, naming what they are (`what`, such as \"definitions\"). (+34 chars)"
   "222  public  defn  ambiguous  [budget index what name-str definitions]  The answer for `name-str` that denotes several `definitions`: an `ambiguous-header`, then one line per candidate within the Output budget. (+71 chars)"
   "232  public  defn  one-definition  [budget index name-str definitions answer-fn]  The answer for `name-str` that resolved to `definitions`: `not-found` for none, `(answer-fn definition)` for exactly one, the `ambiguous` candidates otherwise. (+93 chars)"])

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
