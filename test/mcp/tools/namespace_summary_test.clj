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
   "26  private  def  sentence-marks  #{Character}  The characters that end a sentence of a Docstring summary."
   "36  public  defn  budget  [cfg {:keys [limit offset]}]  Config -> ToolArgs -> OutputBudget  The Output budget of a call with the tool's `args`. (+100 chars)"
   "49  private  defn-  truncation-note  [shown-until total]  Long -> Long -> String  The Truncation note of a page. (+50 chars)"
   "63  public  defn  relative-path  [root-path file-path]  Maybe String -> String -> String  Returns `file-path` relative to the project root `root-path`; paths outside the root are returned unchanged."
   "78  public  defn  collapse-whitespace  [text]  String -> String  `text` with every run of whitespace replaced by one space."
   "85  private  defn-  collapsed-text  [text]  Maybe String -> Maybe String  `text` with whitespace collapsed and trimmed; nil when nothing remains."
   "94  private  defn-  sentence-end  [text]  String -> Maybe Long  The index just past the first sentence of the whitespace-collapsed `text`; nil when the whole text is one sentence. (+105 chars)"
   "116  public  defn  docstring-summary  [doc]  Maybe String -> Maybe String  The Docstring summary of `doc`: its first sentence with the source line breaks joined; nil when the docstring is blank. (+135 chars)"
   "136  public  s/def  :mcp.output/strategy"
   "138  public  s/def  :mcp.output/regex"
   "140  public  s/def  :mcp.output/signature-strategy"
   "146  public  s/def  :mcp.output/summary"
   "148  public  s/def  :mcp.output/signature"
   "150  public  s/def  :mcp.output/definition-doc"
   "157  public  s/def  :mcp.output/text"
   "159  public  s/def  :mcp.output/signature-split"
   "161  public  defmulti  split-signature  SignatureStrategy -> String -> SignatureSplit  Splits the docstring `doc` into its Type signature and the text the Docstring summary is taken from, with the strategy of `signature-strategy`. (+52 chars)"
   "170  public  defmethod  split-signature :default"
   "174  public  defmethod  split-signature :last-paragraph"
   "182  public  defmethod  split-signature :pattern"
   "190  public  defn  definition-doc  [signature-strategy {:keys [kind doc]}]  SignatureStrategy -> Definition -> DefinitionDoc  The Docstring summary and Type signature of `definition`, the signature found by `signature-strategy` and left out of the summary. (+34 chars)"
   "207  private  defn-  answer-text  [shown offset total]  [String] -> Long -> Long -> String  Joins the `shown` findings of a page starting at `offset`, adding a Truncation note when findings remain after them."
   "218  public  def  ellipsis  String  The mark of a cut end of a text."
   "224  public  defn  ellipsis-cut  [room text]  Long -> String -> String  The first `room` characters of `text`, its last one replaced by `…`."
   "231  private  defn-  oversized-first-finding  [cut max-chars page offset total]  (Long -> a -> String) -> Long -> [a] -> Long -> Long -> String  Cuts the first finding of a page that alone exceeds `max-chars` with `cut`, so that paging still advances by one."
   "249  public  s/def  :mcp.output/text-of"
   "251  public  s/def  :mcp.output/cut"
   "253  public  s/def  :mcp.output/render-options"
   "255  public  defn  render  [budget findings] [{:keys [limit max-chars offset]} findings {:keys [text-of cut] :or {text-of identity}}]  OutputBudget -> [String] -> String OutputBudget -> [a] -> RenderOptions -> String  Renders `findings` as an answer within the Output budget."
   "271  public  defn  render-framed  [budget frame findings] [budget {:keys [header footer]} findings options]  OutputBudget -> AnswerFrame -> [String] -> String OutputBudget -> AnswerFrame -> [a] -> RenderOptions -> String  Renders `findings` inside an AnswerFrame. (+113 chars)"
   "293  public  s/def  :mcp.output/what"
   "295  public  s/def  :mcp.output/finding-of"
   "297  public  s/def  :mcp.output/finding-cut"
   "299  private  defn-  single-finding-note  [what shown total]  Keyword -> Long -> Long -> String  The Truncation note of a single finding cut to `shown` of its `total` characters of `what`; it has no paging hint."
   "307  public  defn  cut-finding  [{:keys [what text finding-of] :or {finding-of identity}} room]  FindingCut -> Long -> String  The finding made of the longest prefix of the FindingCut's text that fits `room` characters together with its Truncation note on the next line."
   "338  public  s/def  :mcp.output/budget"
   "340  public  s/def  :mcp.output/index"
   "342  public  s/def  :mcp.output/answer-context"
   "344  public  defrecord  ->AnswerContext  [budget index signature-strategy]"
   "344  public  defrecord  AnswerContext"
   "344  public  defrecord  map->AnswerContext  [m]"
   "346  public  defn  answer-context  [cfg args index]  Config -> ToolArgs -> CodeIndex -> AnswerContext  The AnswerContext of a call with the tool's `args` on `index`."
   "353  public  defn  checked  [index] [index include-tests?]  CodeIndex -> String CodeIndex -> Boolean -> String  What an answer searched: `(checked N files, including tests)`, or excluding tests (which are then not counted) when `include-tests?` is false."
   "367  public  defn  not-found  [index name-str]  CodeIndex -> String -> String  The answer for a name that resolves to nothing. (+26 chars)"
   "376  public  defn  definition-head  [index {:keys [filename row kind arglists] :as definition}]  CodeIndex -> Definition -> String  The start of a Definition's Compact answer line: `path:line kind ns/name [arglists]`."
   "389  public  defn  definition->line  [ctx definition] [{:keys [index signature-strategy]} definition {:keys [signature?]}]  AnswerContext -> Definition -> String AnswerContext -> Definition -> {:signature? Boolean} -> String  The Compact answer line of a Definition. (+216 chars)"
   "409  public  defn  ambiguous-header  [what name-str candidate-count]  String -> String -> Long -> String  The header line for `name-str` that denotes `candidate-count` candidates, naming what they are (`what`, such as \"definitions\")."
   "417  public  defn  ambiguous  [{:keys [budget] :as ctx} what name-str definitions]  AnswerContext -> String -> String -> [Definition] -> String  The answer for `name-str` that denotes several `definitions`: an `ambiguous-header`, then one line per candidate, with its Type signature, within the Output budget."
   "428  public  defn  one-definition  [ctx name-str definitions answer-fn] [{:keys [index]} name-str definitions answer-fn ambiguous-fn]  AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> IO String AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> ([Definition] -> String) -> IO String  The answer for `name-str` that resolved to `definitions`: `not-found` for none, `(answer-fn definition)` for exactly one, and for several `(ambiguous-fn definitions)`, by default the `ambiguous` candidates."])

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
