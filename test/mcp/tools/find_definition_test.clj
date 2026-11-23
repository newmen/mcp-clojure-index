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
  (str "src/mcp/output.clj:60  defn  mcp.output/relative-path  [root-path file-path]  "
       "Maybe String -> String -> String  "
       "Returns `file-path` relative to the project root `root-path`; paths outside the root "
       "are returned unchanged."))

(defn- group
  "The answer group of `name-str` with its `lines`.

  String -> & String -> String"
  [name-str & lines]
  (st/join "\n" (cons name-str lines)))

(deftest finds-definition-by-simple-alias-qualified-and-fully-qualified-name
  (testing "simple"
    (is (= (group "relative-path" relative-path-line) (answer {:names ["relative-path"]}))))
  (testing "alias-qualified, as written in mcp.tools"
    (is (= (group "output/relative-path" relative-path-line)
           (answer {:names ["output/relative-path"]}))))
  (testing "fully qualified"
    (is (= (group "mcp.output/relative-path" relative-path-line)
           (answer {:names ["mcp.output/relative-path"]})))))

(deftest finds-namespace-definition
  (is (= (group "mcp.output"
                "src/mcp/output.clj:1  ns  mcp.output  Output budget (ADR-0004) and Compact answer helpers (ADR-0006).")
         (answer {:names ["mcp.output"]}))))

(deftest ambiguous-simple-name-returns-candidates
  (let [[header & candidates] (st/split-lines (answer {:names ["test-config"]}))]
    (is (= "ambiguous: 3 definitions named test-config, pass a qualified name" header))
    (is (= [["test/mcp/qdrant_test.clj" "mcp.qdrant-test/test-config"]
            ["test/mcp/search_test.clj" "mcp.search-test/test-config"]
            ["test/mcp/tools_test.clj" "mcp.tools-test/test-config"]]
           (map #(rest (re-matches #"(\S+):\d+  def  (\S+)" %)) candidates)))))

(defn- checked-files
  "What a `find_definition` miss on this project's code base says was searched.

  () -> IO String"
  []
  (str "(checked " (count (code-index/files (project/code-index))) " files, including tests)"))

(deftest miss-reports-what-was-searched
  (is (= (str "no-such-definition: not found " (checked-files))
         (answer {:names ["no-such-definition"]}))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 2)
        text (answer cfg {:names ["test-config"]})]
    (is (= 4 (count (st/split-lines text))))
    (is (st/ends-with? text "truncated: shown 2 of 3, pass offset=2 or narrow the query"))
    (is (st/includes? (answer cfg {:names ["test-config"] :offset 2}) "mcp.tools-test/test-config"))
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
    (is (= (group "helper-test" "test/app/core_test.clj:5  deftest  app.core-test/helper-test  []")
           (fixture-answer {:names ["helper-test"]}))))
  (testing "defmethod with its dispatch value"
    (is (= (group "area :circle" "src/app/core.clj:15  defmethod  app.core/area :circle")
           (fixture-answer {:names ["area :circle"]}))))
  (testing "s/def by keyword"
    (is (= (group ":app.core/uuid" "src/app/core.clj:23  s/def  :app.core/uuid")
           (fixture-answer {:names [":app.core/uuid"]}))))
  (testing "s/def of another namespace's keyword"
    (is (= (group ":app.contract.Order/amount" "src/app/core.clj:35  s/def  :app.contract.Order/amount")
           (fixture-answer {:names [":app.contract.Order/amount"]}))))
  (testing "form of a project macro declared via lint-as"
    (is (= (group "handle-uuid" "src/app/core.clj:26  defhandler  app.core/handle-uuid  [req]  Handles a uuid.")
           (fixture-answer {:names ["handle-uuid"]})))))

(deftest description-says-what-grep-cannot-do
  (let [spec (some #(when (= "find_definition" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"alias" (:description spec)))
    (is (re-find #"grep" (:description spec)))
    (is (re-find #"area :circle" (:description spec)))
    (is (re-find #"::uuid" (:description spec)))
    (is (re-find #"doc: \"full\"" (:description spec)))
    (is (= #{:names :doc :ns :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))
    (is (= "Resolve names as written in this namespace (`::k`, aliases, :refer)"
           (get-in spec [:inputSchema :properties :ns :description])))
    (is (= ["summary" "full"] (get-in spec [:inputSchema :properties :doc :enum])))))

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
    (is (= (group "app.summaries/relative-path" (summary-answers "app.summaries/relative-path"))
           (fixture-answer {:names ["app.summaries/relative-path"]}))))
  (testing "a full stop right before the closing quote ends the summary, nothing remains"
    (is (= (group "app.summaries/limit" (summary-answers "app.summaries/limit"))
           (fixture-answer {:names ["app.summaries/limit"]}))))
  (testing "`e.g.` cuts the sentence; a backticked `foo!` does not"
    (is (= (group "app.summaries/cache!" (summary-answers "app.summaries/cache!"))
           (fixture-answer {:names ["app.summaries/cache!"]})))
    (is (= (group "app.summaries/clear!" (summary-answers "app.summaries/clear!"))
           (fixture-answer {:names ["app.summaries/clear!"]}))))
  (testing "every answer stays within the Output budget"
    (is (every? #(<= (count (fixture-answer {:names [%]})) (:output/max-chars project/config))
                (keys summary-answers)))))

(deftest definition-line-shows-the-type-signature-after-the-arglists
  (testing "a `def` whose signature is a bare type"
    (is (= (group "app.signatures/page-size" "src/app/signatures.clj:6  def  app.signatures/page-size  Long  Rows per page.")
           (fixture-answer {:names ["app.signatures/page-size"]}))))
  (testing "a namespace has no signature, its second paragraph stays in the summary"
    (is (= (group "app.signatures"
                  (str "src/app/signatures.clj:1  ns  app.signatures  Type signature fixtures. "
                       "(+64 chars)"))
           (fixture-answer {:names ["app.signatures"]}))))
  (testing "ambiguous candidates show their signatures"
    (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
                "src/app/signatures.clj:12  defn  app.signatures/relative-path  [file-path]  "
                "String -> String  Путь к файлу относительно корня.\n"
                (summary-answers "app.summaries/relative-path"))
           (fixture-answer {:names ["relative-path"]}))))
  (testing "this code base's own docstrings"
    (is (= (group "mcp.output/relative-path" relative-path-line)
           (answer {:names ["mcp.output/relative-path"]})))))

(deftest signature-strategy-comes-from-the-config
  (testing "`:pattern` takes the first group of the regex"
    (is (= (str "app.summaries/relative-path\n"
                "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
                "Maybe String -> String  "
                "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
                "(+38 chars)")
           (fixture-answer (assoc project/config :doc/signature
                                  {:strategy :pattern :regex "(Maybe String -> String) -> String"})
                           {:names ["app.summaries/relative-path"]}))))
  (testing "a config without `:doc/signature` shows no signatures"
    (is (= (str "app.summaries/relative-path\n"
                "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]  "
                "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. "
                "(+71 chars)")
           (fixture-answer (dissoc project/config :doc/signature)
                           {:names ["app.summaries/relative-path"]})))
    (is (= (group "app.signatures/page-size" "src/app/signatures.clj:6  def  app.signatures/page-size  Rows per page. (+4 chars)")
           (fixture-answer (assoc project/config :doc/signature nil)
                           {:names ["app.signatures/page-size"]})))))

(def ^:private full-doc-answers
  "The `find_definition doc: \"full\"` answers on the definitions fixture,
   by name: each Definition line, then its whole docstring indented by two
   spaces with its line breaks.

  {String String}"
  {"app.summaries/relative-path"
   (str "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]\n"
        "  Возвращает путь к файлу относительно корня\n"
        "   проекта, если файл лежит внутри него. Иначе путь\n"
        "   возвращается без изменений.\n"
        "\n"
        "  Maybe String -> String -> String")
   "app.signatures/relative-path"
   (str "src/app/signatures.clj:12  defn  app.signatures/relative-path  [file-path]\n"
        "  Путь к файлу относительно корня.\n"
        "\n"
        "  String -> String")
   "helper-test"
   (str "test/app/core_test.clj:5  deftest  app.core-test/helper-test  []\n"
        "  (no docstring)")})

(deftest full-doc-shows-the-whole-docstring-of-each-definition
  (testing "one Definition: its line without signature and summary, then the docstring"
    (is (= (group "app.summaries/relative-path" (full-doc-answers "app.summaries/relative-path"))
           (fixture-answer {:names ["app.summaries/relative-path"] :doc "full"}))))
  (testing "a Definition without a docstring"
    (is (= (group "helper-test" (full-doc-answers "helper-test"))
           (fixture-answer {:names ["helper-test"] :doc "full"}))))
  (testing "ambiguous: the header, then each candidate with its docstring"
    (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
                (full-doc-answers "app.signatures/relative-path") "\n"
                (full-doc-answers "app.summaries/relative-path"))
           (fixture-answer {:names ["relative-path"] :doc "full"}))))
  (testing "\"summary\" is the default"
    (is (= (fixture-answer {:names ["app.summaries/relative-path"]})
           (fixture-answer {:names ["app.summaries/relative-path"] :doc "summary"})))))

(deftest full-doc-pages-by-definition
  (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
              (full-doc-answers "app.signatures/relative-path") "\n"
              "truncated: shown 1 of 2, pass offset=1 or narrow the query")
         (fixture-answer {:names ["relative-path"] :doc "full" :limit 1})))
  (is (= (str "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
              (full-doc-answers "app.summaries/relative-path"))
         (fixture-answer {:names ["relative-path"] :doc "full" :offset 1}))))

(deftest full-doc-longer-than-the-budget-ends-with-the-docstring-truncation-note
  (let [cfg (assoc project/config :output/max-chars 178)]
    (is (= (str "app.summaries/relative-path\n"
                "src/app/summaries.clj:4  defn  app.summaries/relative-path  [root file-path]\n"
                "  Возвращает путь к файлу отно\n"
                "truncated: docstring shown 28 of 155 chars")
           (fixture-answer cfg {:names ["app.summaries/relative-path"] :doc "full"})))))

;; ---------------------------------------------------------------------------
;; Several names
;; ---------------------------------------------------------------------------

(def ^:private page-size-line
  "src/app/signatures.clj:6  def  app.signatures/page-size  Long  Rows per page.")

(def ^:private limit-line
  "src/app/summaries.clj:13  def  app.summaries/limit  Lines per page.")

(def ^:private signatures-relative-path-line
  (str "src/app/signatures.clj:12  defn  app.signatures/relative-path  [file-path]  "
       "String -> String  Путь к файлу относительно корня."))

(deftest several-names-answer-in-groups-separated-by-a-blank-line
  (is (= (str (group "app.signatures/page-size" page-size-line) "\n\n"
              (group "app.summaries/limit" limit-line))
         (fixture-answer {:names ["app.signatures/page-size" "app.summaries/limit"]}))))

(deftest repeated-names-answer-once
  (is (= (str (group "app.summaries/limit" limit-line) "\n\n"
              (group "limit" limit-line))
         (fixture-answer {:names ["app.summaries/limit" "app.summaries/limit" "limit"]}))))

(deftest a-missing-or-ambiguous-name-does-not-hide-the-others
  (is (= (str "no-such-definition: not found (checked "
              (count (code-index/files @definitions-fixture)) " files, including tests)\n"
              "\n"
              "ambiguous: 2 definitions named relative-path, pass a qualified name\n"
              signatures-relative-path-line "\n"
              (summary-answers "app.summaries/relative-path") "\n"
              "\n"
              (group "app.summaries/limit" limit-line))
         (fixture-answer {:names ["no-such-definition" "relative-path" "app.summaries/limit"]}))))

(deftest full-doc-applies-to-every-name
  (is (= (str (group "helper-test" (full-doc-answers "helper-test")) "\n\n"
              (group "app.signatures/relative-path" (full-doc-answers "app.signatures/relative-path")))
         (fixture-answer {:names ["helper-test" "app.signatures/relative-path"] :doc "full"}))))

(deftest paging-counts-definitions-across-groups-and-repeats-the-header
  (let [names ["app.summaries/limit" "relative-path" "app.signatures/page-size"]
        ambiguous-header "ambiguous: 2 definitions named relative-path, pass a qualified name"]
    (testing "a page ends inside a group"
      (is (= (str (group "app.summaries/limit" limit-line) "\n\n"
                  ambiguous-header "\n"
                  signatures-relative-path-line "\n"
                  "truncated: shown 2 of 4, pass offset=2 or narrow the query")
             (fixture-answer {:names names :limit 2}))))
    (testing "the next page starts inside that group and repeats its header"
      (is (= (str ambiguous-header "\n"
                  (summary-answers "app.summaries/relative-path") "\n"
                  "\n"
                  (group "app.signatures/page-size" page-size-line))
             (fixture-answer {:names names :offset 2}))))))

(deftest empty-list-of-names-says-so
  (is (= "no names given" (fixture-answer {:names []})))
  (is (= "no names given" (fixture-answer {:names ["  "]}))))

(defn- not-found-header
  "The header of the group of `name-str`, found nowhere in the fixture.

  String -> IO String"
  [name-str]
  (str name-str ": not found (checked "
       (count (code-index/files @definitions-fixture)) " files, including tests)"))

(deftest a-missing-name-takes-no-place-in-the-page
  (let [names ["app.summaries/limit" "no-such-definition" "app.signatures/page-size" "nope"]]
    (testing "a page of two Definitions shows both, with the missing names where they fall"
      (is (= (str (group "app.summaries/limit" limit-line) "\n\n"
                  (not-found-header "no-such-definition") "\n\n"
                  (group "app.signatures/page-size" page-size-line) "\n\n"
                  (not-found-header "nope"))
             (fixture-answer {:names names :limit 2}))))
    (testing "a missing name shows on the page of the Definition after it"
      (is (= (str (group "app.summaries/limit" limit-line) "\n"
                  "truncated: shown 1 of 2, pass offset=1 or narrow the query")
             (fixture-answer {:names names :limit 1})))
      (is (= (str (not-found-header "no-such-definition") "\n\n"
                  (group "app.signatures/page-size" page-size-line) "\n\n"
                  (not-found-header "nope"))
             (fixture-answer {:names names :offset 1}))))
    (testing "only missing names"
      (is (= (str (not-found-header "no-such-definition") "\n\n" (not-found-header "nope"))
             (fixture-answer {:names ["no-such-definition" "nope"]}))))))

;; ---------------------------------------------------------------------------
;; Description from comments
;; ---------------------------------------------------------------------------

(def ^:private comment-answers
  "The `find_definition` answers on the Description-from-comments fixture,
   by name.

  {String String}"
  {":app.comments/order-id"
   (str "src/app/comments.clj:15  s/def  :app.comments/order-id  String  "
        "An order identifier: a non-blank string. (+21 chars)")
   "app.comments/max-retries"
   "src/app/comments.clj:19  def  app.comments/max-retries  Retries before giving up."
   "app.comments/batch-size"
   "src/app/comments.clj:9  def  app.comments/batch-size"
   "app.comments/timeout-ms"
   "src/app/comments.clj:22  def  app.comments/timeout-ms  Milliseconds to wait for a reply."
   "app.comments"
   "src/app/comments.clj:3  ns  app.comments"})

(deftest a-comment-block-directly-above-a-form-is-its-description
  (testing "a spec: the Type signature in the last paragraph, the Docstring summary of the rest"
    (is (= (group ":app.comments/order-id" (comment-answers ":app.comments/order-id"))
           (fixture-answer {:names [":app.comments/order-id"]}))))
  (testing "a `def` under an indented `;;;` line"
    (is (= (group "app.comments/max-retries" (comment-answers "app.comments/max-retries"))
           (fixture-answer {:names ["app.comments/max-retries"]})))))

(deftest a-comment-separated-by-a-blank-line-is-no-description
  (is (= (group "app.comments/batch-size" (comment-answers "app.comments/batch-size"))
         (fixture-answer {:names ["app.comments/batch-size"]}))))

(deftest a-docstring-wins-over-a-comment
  (is (= (group "app.comments/timeout-ms" (comment-answers "app.comments/timeout-ms"))
         (fixture-answer {:names ["app.comments/timeout-ms"]}))))

(deftest a-namespace-header-comment-is-no-description
  (is (= (group "app.comments" (comment-answers "app.comments"))
         (fixture-answer {:names ["app.comments"]}))))

(deftest full-doc-shows-the-whole-comment-description
  (is (= (group ":app.comments/order-id"
                (str "src/app/comments.clj:15  s/def  :app.comments/order-id\n"
                     "  An order identifier: a non-blank\n"
                     "  string. Assigned by the shop.\n"
                     "\n"
                     "  String"))
         (fixture-answer {:names [":app.comments/order-id"] :doc "full"}))))

(deftest signature-strategy-of-the-config-applies-to-a-comment-description
  (is (= (group ":app.comments/order-id"
                (str "src/app/comments.clj:15  s/def  :app.comments/order-id  "
                     "An order identifier: a non-blank string. (+28 chars)"))
         (fixture-answer (assoc project/config :doc/signature nil)
                         {:names [":app.comments/order-id"]}))))

;; ---------------------------------------------------------------------------
;; Names as written in a namespace
;; ---------------------------------------------------------------------------

(defn- ns-answer
  "The answer text of `find_definition` on the namespaces fixture.

  ToolArgs -> IO String"
  [args]
  (project/answer-text project/config
                       (tool/call :find_definition args
                                  {:config project/config :code-index (project/namespaces-index)})))

(def ^:private alpha-id-line "src/ns_app/alpha.clj:5  s/def  :ns-app.alpha/id")

(def ^:private beta-id-line "src/ns_app/beta.clj:5  s/def  :ns-app.beta/id")

(deftest auto-resolved-keyword-resolves-in-the-given-namespace
  (is (= (group "::id" alpha-id-line) (ns-answer {:names ["::id"] :ns "ns-app.alpha"})))
  (is (= (group "::id" beta-id-line) (ns-answer {:names ["::id"] :ns "ns-app.beta"}))))

(def ^:private alpha-fetch-line
  "src/ns_app/alpha.clj:7  defn  ns-app.alpha/fetch  [id]  Fetches from alpha.")

(def ^:private beta-fetch-line
  "src/ns_app/beta.clj:7  defn  ns-app.beta/fetch  [id]  Fetches from beta.")

(deftest one-alias-for-different-targets-resolves-per-namespace
  (testing "::alias/k"
    (is (= (group "::store/id" alpha-id-line) (ns-answer {:names ["::store/id"] :ns "ns-app.one"})))
    (is (= (group "::store/id" beta-id-line) (ns-answer {:names ["::store/id"] :ns "ns-app.two"}))))
  (testing "alias/name"
    (is (= (group "store/fetch" alpha-fetch-line) (ns-answer {:names ["store/fetch"] :ns "ns-app.one"})))
    (is (= (group "store/fetch" beta-fetch-line) (ns-answer {:names ["store/fetch"] :ns "ns-app.two"})))))

(deftest bare-name-resolves-to-the-namespace-definition-then-its-refers
  (testing "its own Definition"
    (is (= (group "run" "src/ns_app/two.clj:4  defn  ns-app.two/run  []  Runs on beta.")
           (ns-answer {:names ["run"] :ns "ns-app.two"}))))
  (testing "a referred name"
    (is (= (group "fetch" alpha-fetch-line) (ns-answer {:names ["fetch"] :ns "ns-app.one"})))
    (is (= (group "fetch" beta-fetch-line) (ns-answer {:names ["fetch"] :ns "ns-app.two"})))))

(deftest qualified-names-and-keywords-are-unaffected-by-the-namespace
  (is (= (group "ns-app.beta/fetch" beta-fetch-line)
         (ns-answer {:names ["ns-app.beta/fetch"] :ns "ns-app.one"})))
  (is (= (group ":ns-app.beta/id" beta-id-line)
         (ns-answer {:names [":ns-app.beta/id"] :ns "ns-app.one"})))
  (is (= (group "ns-app.beta" "src/ns_app/beta.clj:1  ns  ns-app.beta  Beta store.")
         (ns-answer {:names ["ns-app.beta"] :ns "ns-app.one"})))
  (testing "a qualifier that is neither an alias nor a namespace of the project"
    (is (= "clojure.string/join: not found (checked 4 files, including tests)"
           (ns-answer {:names ["clojure.string/join"] :ns "ns-app.one"})))))

(deftest name-not-resolvable-in-the-namespace-lists-all-candidates
  (testing "a name the namespace neither defines nor refers"
    (is (= (str "not resolvable in ns-app.alpha, all candidates:\n"
                "ambiguous: 2 definitions named run, pass a qualified name\n"
                "src/ns_app/one.clj:4  defn  ns-app.one/run  []  Runs on alpha.\n"
                "src/ns_app/two.clj:4  defn  ns-app.two/run  []  Runs on beta.")
           (ns-answer {:names ["run"] :ns "ns-app.alpha"}))))
  (testing "an alias the namespace does not have"
    (is (= (str "not resolvable in ns-app.alpha, all candidates:\n"
                "ambiguous: 2 definitions named store/fetch, pass a qualified name\n"
                alpha-fetch-line "\n"
                beta-fetch-line)
           (ns-answer {:names ["store/fetch"] :ns "ns-app.alpha"})))))

(deftest unknown-namespace-answers-only-that
  (is (= "unknown namespace: ns-app.nope" (ns-answer {:names ["fetch" "::id"] :ns "ns-app.nope"}))))

(deftest namespace-applies-to-every-name-of-a-batch
  (is (= (str (group "::store/id" alpha-id-line) "\n\n"
              "not resolvable in ns-app.one, all candidates:\n"
              "missing: not found (checked 4 files, including tests)\n\n"
              (group "fetch" alpha-fetch-line))
         (ns-answer {:names ["::store/id" "missing" "fetch"] :ns "ns-app.one"}))))
