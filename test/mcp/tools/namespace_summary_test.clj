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
   "78  private  defn-  sentence-end  [text]  String -> Maybe Long  The index just past the first sentence of the whitespace-collapsed `text`; nil when the whole text is one sentence. (+105 chars)"
   "100  public  defn  docstring-summary  [doc]  Maybe String -> Maybe String  The Docstring summary of `doc`: its first sentence with the source line breaks joined; nil when the docstring is blank. (+135 chars)"
   "121  public  s/def  :mcp.output/strategy"
   "123  public  s/def  :mcp.output/regex"
   "125  public  s/def  :mcp.output/signature-strategy"
   "131  public  s/def  :mcp.output/summary"
   "133  public  s/def  :mcp.output/signature"
   "135  public  s/def  :mcp.output/definition-doc"
   "137  private  defn-  collapse-whitespace  [text]  String -> Maybe String  `text` with every run of whitespace replaced by one space, trimmed; nil when nothing remains."
   "147  public  defmulti  split-signature  SignatureStrategy -> String -> {:text String :signature Maybe String}  Splits the docstring `doc` into its Type signature and the text the Docstring summary is taken from, with the strategy of `signature-strategy`. (+52 chars)"
   "156  public  defmethod  split-signature :default"
   "160  public  defmethod  split-signature :last-paragraph"
   "168  public  defmethod  split-signature :pattern"
   "176  public  defn  definition-doc  [signature-strategy {:keys [kind doc]}]  SignatureStrategy -> Definition -> DefinitionDoc  The Docstring summary and Type signature of `definition`, the signature found by `signature-strategy` and left out of the summary. (+34 chars)"
   "193  private  defn-  answer-text  [shown offset total]  [String] -> Long -> Long -> String  Joins the `shown` findings of a page starting at `offset`, adding a Truncation note when findings remain after them."
   "204  private  defn-  ellipsis-cut  [room finding]  Long -> String -> String  The first `room` characters of `finding`, its last one replaced by `…`."
   "211  private  defn-  oversized-first-finding  [cut max-chars page offset total]  (Long -> String -> String) -> Long -> [String] -> Long -> Long -> String  Cuts the first finding of a page that alone exceeds `max-chars` with `cut`, so that paging still advances by one."
   "227  public  s/def  :mcp.output/cut"
   "229  public  s/def  :mcp.output/render-options"
   "231  public  defn  render  [budget findings] [{:keys [limit max-chars offset]} findings {:keys [cut]}]  OutputBudget -> [String] -> String OutputBudget -> [String] -> RenderOptions -> String  Renders `findings` (one string per finding) as an answer within the Output budget."
   "246  public  defn  render-framed  [budget frame findings] [budget {:keys [header footer]} findings options]  OutputBudget -> AnswerFrame -> [String] -> String OutputBudget -> AnswerFrame -> [String] -> RenderOptions -> String  Renders `findings` inside an AnswerFrame. (+113 chars)"
   "263  private  defn-  single-finding-note  [what shown total]  String -> Long -> Long -> String  The Truncation note of a single finding cut to `shown` of its `total` characters of `what` (\"docstring\", \"source\"); it has no paging hint."
   "271  public  defn  cut-finding  [what finding-of text room]  String -> (String -> String) -> String -> Long -> String  The finding `finding-of` makes of the longest prefix of `text` that fits `room` characters together with the Truncation note of `what` on the next line."
   "298  public  defn  checked  [index] [index include-tests?]  CodeIndex -> String CodeIndex -> Boolean -> String  What an answer searched: `(checked N files, including tests)`, or excluding tests (which are then not counted) when `include-tests?` is false."
   "312  public  defn  not-found  [index name-str]  CodeIndex -> String -> String  The answer for a name that resolves to nothing. (+26 chars)"
   "321  public  defn  definition-head  [index {:keys [filename row kind arglists] :as definition}]  CodeIndex -> Definition -> String  The start of a Definition's Compact answer line: `path:line kind ns/name [arglists]`."
   "334  public  defn  definition->line  [signature-strategy index definition] [signature-strategy index definition {:keys [signature?]}]  SignatureStrategy -> CodeIndex -> Definition -> String SignatureStrategy -> CodeIndex -> Definition -> {:signature? Boolean} -> String  The Compact answer line of a Definition. (+216 chars)"
   "354  public  defn  ambiguous-header  [what name-str candidate-count]  String -> String -> Long -> String  The header line for `name-str` that denotes `candidate-count` candidates, naming what they are (`what`, such as \"definitions\")."
   "362  public  defn  ambiguous  [budget signature-strategy index what name-str definitions]  OutputBudget -> SignatureStrategy -> CodeIndex -> String -> String -> [Definition] -> String  The answer for `name-str` that denotes several `definitions`: an `ambiguous-header`, then one line per candidate, with its Type signature, within the Output budget."
   "373  public  defn  one-definition  [budget signature-strategy index name-str definitions answer-fn]  OutputBudget -> SignatureStrategy -> CodeIndex -> String -> [Definition] -> (Definition -> IO String) -> IO String  The answer for `name-str` that resolved to `definitions`: `not-found` for none, `(answer-fn definition)` for exactly one, the `ambiguous` candidates otherwise."])

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
