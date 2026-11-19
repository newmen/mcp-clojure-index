(ns mcp.tools.namespace-summary-test
  "`namespace_summary` on this project's own code base."
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [mcp.test-project :as project]
            [mcp.tools :as tools]))

(set! *warn-on-reflection* true)

(defn- answer
  ([args] (answer project/config args))
  ([cfg args]
   (-> (tools/handle-tool "namespace_summary" args
                          {:config cfg :code-index (project/code-index)})
       :content first :text)))

(def ^:private output-definitions
  "Hand-checked Definitions of `src/mcp/output.clj`."
  ["11  private  defn-  effective-limit  [cfg args]"
   "16  private  defn-  truncation-note  [shown-until total]"
   "25  public  defn  relative-path  [root-path file-path]  Return `file-path` relative to the project root `root-path`; paths"
   "38  public  defn  first-line  [text]  First non-blank line of `text` (e.g. a docstring), trimmed; nil if none."
   "50  private  defn-  answer-text  [shown offset total]  Join the `shown` findings of a page starting at `offset`, adding a"
   "59  private  defn-  oversized-first-finding  [cfg page offset total]  Answer for a page whose first finding alone exceeds `:output/max-chars`:"
   "70  public  defn  render  [cfg args findings]  Render `findings` (one string per finding) as an answer within the Output"])

(def ^:private output-requirers
  "Hand-checked namespaces that require `mcp.output` (more may be added)."
  #{"mcp.output-test" "mcp.tools.semantic-search" "mcp.tools.find-definition"})

(defn- required-by
  [text]
  (some->> (s/split-lines text)
           (some #(second (re-matches #"required by: (.*)" %)))
           (#(s/split % #", "))
           set))

(deftest summarises-every-definition-of-a-namespace
  (let [[header & lines] (s/split-lines (answer {:ns "mcp.output"}))]
    (is (= "ns mcp.output  src/mcp/output.clj  Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
           header))
    (is (= output-definitions (butlast lines)))))

(deftest lists-namespaces-that-require-it
  (let [text (answer {:ns "mcp.output"})
        requirers (required-by text)]
    (is (s/starts-with? (last (s/split-lines text)) "required by: "))
    (is (every? requirers output-requirers))
    (is (not (contains? requirers "mcp.output")))))

(deftest namespace-nobody-requires
  (is (s/ends-with? (answer {:ns "mcp.output-test"}) "required by: none")))

(deftest unknown-namespace-returns-close-candidates
  (testing "misspelt"
    (let [text (answer {:ns "mcp.outptu"})]
      (is (s/starts-with? text "not found: namespace mcp.outptu"))
      (is (s/includes? text "mcp.output"))))
  (testing "partial name"
    (is (s/includes? (answer {:ns "find-definition"}) "mcp.tools.find-definition")))
  (testing "nothing close"
    (is (re-matches #"not found: namespace zzzzzzzz \(checked \d+ files, including tests\)"
                    (answer {:ns "zzzzzzzz"})))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 3)
        text (answer cfg {:ns "mcp.output"})
        lines (s/split-lines text)]
    (is (= 5 (count lines)))
    (is (= (take 3 output-definitions) (take 3 (rest lines))))
    (is (re-matches #"truncated: shown 3 of \d+, pass offset=3 or narrow the query" (last lines)))
    (is (= (drop 3 (take 6 output-definitions))
           (take 3 (rest (s/split-lines (answer cfg {:ns "mcp.output" :offset 3}))))))
    (is (<= (count text) (:output/max-chars cfg))))
  (testing "character ceiling"
    (let [cfg (assoc project/config :output/max-chars 300)
          text (answer cfg {:ns "mcp.output"})]
      (is (<= (count text) 300))
      (is (re-find #"truncated: shown \d+ of \d+" text))))
  (testing "default budget"
    (is (<= (count (answer {:ns "mcp.code-index"})) (:output/max-chars project/config)))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "namespace_summary" (:name %)) %)
                   (:tools (tools/handle-tools-list {})))]
    (is (re-find #"grep" (:description spec)))
    (is (= #{:ns :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))
    (is (= ["ns"] (get-in spec [:inputSchema :required])))))
