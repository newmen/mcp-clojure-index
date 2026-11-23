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

(def ^:private output-header
  "The hand-checked header line of the answer for `mcp.output`.

  String"
  "ns mcp.output  src/mcp/output.clj  Output budget (ADR-0004) and Compact answer helpers (ADR-0006).")

(def ^:private output-definitions
  "Hand-checked Definitions of `src/mcp/output.clj` in the default answer,
   without Type signatures and Docstring summaries.

  [String]"
  ["15  public  s/def  ::header"
   "17  public  s/def  ::footer"
   "19  public  s/def  ::answer-frame"
   "25  public  s/def  ::output-budget"
   "27  public  defrecord  ->OutputBudget  [limit max-chars offset]"
   "27  public  defrecord  OutputBudget"
   "27  public  defrecord  map->OutputBudget  [m]"
   "33  public  defn  budget  [cfg {:keys [limit offset]}]"
   "46  private  defn-  truncation-note  [shown-until total]"
   "60  public  defn  relative-path  [root-path file-path]"
   "75  private  defn-  answer-text  [shown offset total]"
   "86  private  defn-  oversized-first-finding  [cut max-chars page offset total]"
   "104  public  s/def  ::text-of"
   "106  public  s/def  ::cut"
   "108  public  s/def  ::render-options"
   "110  public  defn  render  [budget findings] [{:keys [limit max-chars offset]} findings {:keys [text-of cut] :or {text-of identity}}]"
   "126  public  defn  render-framed  [budget frame findings] [budget {:keys [header footer]} findings options]"
   "148  public  s/def  ::what"
   "150  public  s/def  ::text"
   "152  public  s/def  ::finding-of"
   "154  public  s/def  ::finding-cut"
   "156  private  defn-  single-finding-note  [what shown total]"
   "164  public  defn  cut-finding  [{:keys [what text finding-of] :or {finding-of identity}} room]"
   "195  public  s/def  ::budget"
   "197  public  s/def  ::index"
   "199  public  s/def  ::signature-strategy"
   "201  public  s/def  ::answer-context"
   "203  public  defrecord  ->AnswerContext  [budget index signature-strategy]"
   "203  public  defrecord  AnswerContext"
   "203  public  defrecord  map->AnswerContext  [m]"
   "205  public  defn  answer-context  [cfg args index]"
   "212  public  defn  checked  [index] [index include-tests?]"
   "226  public  defn  not-found  [index name-str]"
   "235  public  defn  definition-head  [index {:keys [filename row kind arglists] :as definition}]"
   "248  public  defn  definition->line  [ctx definition] [{:keys [index signature-strategy]} definition {:keys [signature?]}]"
   "268  public  defn  ambiguous-header  [what name-str candidate-count]"
   "276  public  defn  ambiguous  [{:keys [budget] :as ctx} what name-str definitions]"
   "287  public  defn  one-definition  [ctx name-str definitions answer-fn] [{:keys [index]} name-str definitions answer-fn ambiguous-fn]"])

(def ^:private output-detailed-definitions
  "Hand-checked Definitions of `src/mcp/output.clj` in the answer with
   `detail: true`.

  [String]"
  ["15  public  s/def  ::header"
   "17  public  s/def  ::footer"
   "19  public  s/def  ::answer-frame"
   "25  public  s/def  ::output-budget"
   "27  public  defrecord  ->OutputBudget  [limit max-chars offset]"
   "27  public  defrecord  OutputBudget"
   "27  public  defrecord  map->OutputBudget  [m]"
   "33  public  defn  budget  [cfg {:keys [limit offset]}]  Config -> ToolArgs -> OutputBudget  The Output budget of a call with the tool's `args`. (+100 chars)"
   "46  private  defn-  truncation-note  [shown-until total]  Long -> Long -> String  The Truncation note of a page. (+50 chars)"
   "60  public  defn  relative-path  [root-path file-path]  Maybe String -> String -> String  Returns `file-path` relative to the project root `root-path`; paths outside the root are returned unchanged."
   "75  private  defn-  answer-text  [shown offset total]  [String] -> Long -> Long -> String  Joins the `shown` findings of a page starting at `offset`, adding a Truncation note when findings remain after them."
   "86  private  defn-  oversized-first-finding  [cut max-chars page offset total]  (Long -> a -> String) -> Long -> [a] -> Long -> Long -> String  Cuts the first finding of a page that alone exceeds `max-chars` with `cut`, so that paging still advances by one."
   "104  public  s/def  ::text-of"
   "106  public  s/def  ::cut"
   "108  public  s/def  ::render-options"
   "110  public  defn  render  [budget findings] [{:keys [limit max-chars offset]} findings {:keys [text-of cut] :or {text-of identity}}]  OutputBudget -> [String] -> String OutputBudget -> [a] -> RenderOptions -> String  Renders `findings` as an answer within the Output budget."
   "126  public  defn  render-framed  [budget frame findings] [budget {:keys [header footer]} findings options]  OutputBudget -> AnswerFrame -> [String] -> String OutputBudget -> AnswerFrame -> [a] -> RenderOptions -> String  Renders `findings` inside an AnswerFrame. (+113 chars)"
   "148  public  s/def  ::what"
   "150  public  s/def  ::text"
   "152  public  s/def  ::finding-of"
   "154  public  s/def  ::finding-cut"
   "156  private  defn-  single-finding-note  [what shown total]  Keyword -> Long -> Long -> String  The Truncation note of a single finding cut to `shown` of its `total` characters of `what`; it has no paging hint."
   "164  public  defn  cut-finding  [{:keys [what text finding-of] :or {finding-of identity}} room]  FindingCut -> Long -> String  The finding made of the longest prefix of the FindingCut's text that fits `room` characters together with its Truncation note on the next line."
   "195  public  s/def  ::budget"
   "197  public  s/def  ::index"
   "199  public  s/def  ::signature-strategy"
   "201  public  s/def  ::answer-context"
   "203  public  defrecord  ->AnswerContext  [budget index signature-strategy]"
   "203  public  defrecord  AnswerContext"
   "203  public  defrecord  map->AnswerContext  [m]"
   "205  public  defn  answer-context  [cfg args index]  Config -> ToolArgs -> CodeIndex -> AnswerContext  The AnswerContext of a call with the tool's `args` on `index`."
   "212  public  defn  checked  [index] [index include-tests?]  CodeIndex -> String CodeIndex -> Boolean -> String  What an answer searched: `(checked N files, including tests)`, or excluding tests (which are then not counted) when `include-tests?` is false."
   "226  public  defn  not-found  [index name-str]  CodeIndex -> String -> String  The answer for a name that resolves to nothing. (+26 chars)"
   "235  public  defn  definition-head  [index {:keys [filename row kind arglists] :as definition}]  CodeIndex -> Definition -> String  The start of a Definition's Compact answer line: `path:line kind ns/name [arglists]`."
   "248  public  defn  definition->line  [ctx definition] [{:keys [index signature-strategy]} definition {:keys [signature?]}]  AnswerContext -> Definition -> String AnswerContext -> Definition -> {:signature? Boolean} -> String  The Compact answer line of a Definition. (+216 chars)"
   "268  public  defn  ambiguous-header  [what name-str candidate-count]  String -> String -> Long -> String  The header line for `name-str` that denotes `candidate-count` candidates, naming what they are (`what`, such as \"definitions\")."
   "276  public  defn  ambiguous  [{:keys [budget] :as ctx} what name-str definitions]  AnswerContext -> String -> String -> [Definition] -> String  The answer for `name-str` that denotes several `definitions`: an `ambiguous-header`, then one line per candidate, with its Type signature, within the Output budget."
   "287  public  defn  one-definition  [ctx name-str definitions answer-fn] [{:keys [index]} name-str definitions answer-fn ambiguous-fn]  AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> IO String AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> ([Definition] -> String) -> IO String  The answer for `name-str` that resolved to `definitions`: `not-found` for none, `(answer-fn definition)` for exactly one, and for several `(ambiguous-fn definitions)`, by default the `ambiguous` candidates."])

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
  (assoc project/config :output/max-chars 10000 :output/limit 100))

(deftest summarises-every-definition-of-a-namespace
  (let [[header & lines] (st/split-lines (answer roomy-config {:ns "mcp.output"}))]
    (is (= output-header header))
    (is (= output-definitions (butlast lines)))))

(deftest detail-adds-type-signatures-and-docstring-summaries
  (let [[header & lines] (st/split-lines (answer roomy-config {:ns "mcp.output" :detail true}))]
    (is (= output-header header))
    (is (= output-detailed-definitions (butlast lines)))))

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

(deftest specs-of-the-namespace-are-written-as-auto-resolved-keywords
  (let [lines (-> (tool/call :namespace_summary {:ns "app.core"}
                             {:config definitions-fixture-config
                              :code-index (code-index/build definitions-fixture-config)})
                  :content first :text st/split-lines set)]
    (is (contains? lines "23  public  s/def  ::uuid"))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "namespace_summary" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"grep" (:description spec)))
    (is (= #{:ns :detail :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))
    (is (= ["ns"] (get-in spec [:inputSchema :required])))))
