(ns mcp.tools.find-usages-test
  "Acceptance cases 1, 2 and 3 of the spec, on
   fixture projects under `test-resources/usages/` and on this project's
   own code base."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn- fixture-index
  "Builds the code index of the usages fixture project `project-dir`.

  String -> IO CodeIndex"
  [project-dir]
  (code-index/build
   (assoc project/config :index/root-path
          (.getCanonicalPath (File. (str "test-resources/usages/" project-dir))))))

(def ^:private lein-project (delay (fixture-index "lein_project")))
(def ^:private deps-project (delay (fixture-index "deps_project")))
(def ^:private plain-project (delay (fixture-index "plain_project")))
(def ^:private keyword-project (delay (fixture-index "keyword_project")))

(defn- answer
  "The answer text of `find_usages` on `code-index`.

  CodeIndex -> ToolArgs -> IO String
  Config -> CodeIndex -> ToolArgs -> IO String"
  ([code-index args] (answer project/config code-index args))
  ([cfg code-index args]
   (project/answer-text cfg (tool/call :find_usages args {:config cfg :code-index code-index}))))

(defn- answer-lines
  "The lines of the answer of `find_usages` on `code-index`.

  CodeIndex -> ToolArgs -> IO [String]"
  [code-index args]
  (st/split-lines (answer code-index args)))

(def ^:private get-uuid-usages
  ["src/app/core.clj:10:11  value  app.core/uses-value"
   "src/app/core.clj:11:15  value  app.core/uses-value"
   "src/app/core.clj:12:16  value  app.core/uses-value"
   "src/app/core.clj:13:11  value  app.core/uses-value"
   "src/app/core.clj:17:4  in-comment  app.core"
   "spec/app/core_spec.clj:6:4  call  app.core-spec/check-uuid"
   "spec/app/core_spec.clj:9:17  redef  app.core-spec/get-uuid-test"
   "src/app/other.clj:2:37  require  app.other"
   "src/app/other.clj:6:5  call  app.other/caller"
   "src/app/other.clj:7:5  call  app.other/caller"
   "src/app/other.clj:8:5  call  app.other/caller"
   "src/app/other.clj:9:12  var-ref  app.other/caller"
   "src/app/other.clj:10:9  var-ref  app.other/caller"])

(deftest finds-usages-through-alias-refer-var-value-redef-and-comment
  (let [[header & usages] (answer-lines @lein-project {:targets ["get-uuid"]})]
    (is (= "app.core/get-uuid: 13 usages" header))
    (is (= (sort get-uuid-usages) (sort usages)))))

(deftest finds-require-usages-of-a-namespace
  (is (= ["app.core: 2 usages"
          "spec/app/core_spec.clj:2:14  require  app.core-spec"
          "src/app/other.clj:2:14  require  app.other"]
         (answer-lines @lein-project {:targets ["app.core"]}))))

(deftest several-targets-produce-one-group-per-target
  (let [lines (answer-lines @lein-project {:targets ["c/get-uuid" "app.core/uses-value"]})
        [uuid-group uses-value-group] (split-with (complement #{"app.core/uses-value: 1 usage"})
                                                  lines)]
    (is (= "app.core/get-uuid: 13 usages" (first uuid-group)))
    (is (= 14 (count uuid-group)))
    (is (= ["app.core/uses-value: 1 usage"
            "src/app/other.clj:11:5  call  app.other/caller"]
           uses-value-group))))

(deftest kinds-filter-the-usages
  (is (= ["app.core/get-uuid: 2 usages"
          "src/app/other.clj:9:12  var-ref  app.other/caller"
          "src/app/other.clj:10:9  var-ref  app.other/caller"]
         (answer-lines @lein-project {:targets ["get-uuid"] :kinds ["var-ref"]}))))

(deftest kinds-can-exclude-in-comment-usages
  (let [[header & usages] (answer-lines @lein-project {:targets ["get-uuid"]
                                                       :kinds ["call" "value" "var-ref"
                                                               "redef" "require"]})]
    (is (= "app.core/get-uuid: 12 usages" header))
    (is (= (sort (remove #(st/includes? % "in-comment") get-uuid-usages)) (sort usages)))))

(deftest test-usages-are-dropped-when-tests-are-excluded
  (testing "Test path from project.clj :test-paths"
    (is (not-any? #(st/starts-with? % "spec/")
                  (answer-lines @lein-project {:targets ["get-uuid"] :include_tests false})))
    (is (= "app.core/get-uuid: 11 usages"
           (first (answer-lines @lein-project {:targets ["get-uuid"] :include_tests false})))))
  (testing "Test path from a deps.edn test alias :extra-paths"
    (is (= ["lib.a/f: 1 usage" "src/lib/a.clj:5:14  call  lib.a/g"]
           (answer-lines @deps-project {:targets ["lib.a/f"] :include_tests false}))))
  (testing "fallback: test/ and *_test files"
    (is (= 3 (dec (count (answer-lines @plain-project {:targets ["plain.a/f"]})))))
    (is (= ["plain.a/f: 1 usage" "src/plain/a.clj:5:14  call  plain.a/g"]
           (answer-lines @plain-project {:targets ["plain.a/f"] :include_tests false})))))

(deftest empty-result-says-what-was-searched
  (testing "including tests"
    (is (= "no usages of app.core-spec/get-uuid-test (checked 4 files, including tests)"
           (answer @lein-project {:targets ["get-uuid-test"]}))))
  (testing "excluding tests"
    (is (= "no usages of app.core/uses-value (checked 3 files, excluding tests)"
           (answer @lein-project {:targets ["uses-value"] :kinds ["value"] :include_tests false})))))

(deftest unknown-and-ambiguous-targets
  (is (= "not found: no-such-fn (checked 4 files, including tests)"
         (answer @lein-project {:targets ["no-such-fn"]})))
  (is (= (str "ambiguous: 2 definitions named check, pass a qualified name\n"
              "src/plain/c_test.clj:4  defn  plain.c-test/check  []\n"
              "test/plain/b.clj:4  defn  plain.b/check  []")
         (answer @plain-project {:targets ["check"]}))))

(deftest defmethod-target-points-to-its-multimethod
  (let [definitions-project (code-index/build
                             (assoc project/config :index/root-path
                                    (.getCanonicalPath (File. "test-resources/fixtures/definitions_project"))))]
    (is (= (str "app.core/area :circle is a defmethod: it is called through its multimethod, "
                "find usages of app.core/area")
           (answer definitions-project {:targets ["area :circle"]})))))

(deftest finds-usages-on-own-code-base
  (testing (str "line and column numbers are left out: they move with every edit; "
                "code inside a defmethod is enclosed by `multimethod dispatch-value`")
    (is (= ["mcp.output/definition->line: 3 usages"
            "src/mcp/output.clj:L:C  value  mcp.output/ambiguous"
            "src/mcp/tools/find_definition.clj:L:C  call  mcp.tool/call :find_definition"
            "src/mcp/tools/find_usages.clj:L:C  value  mcp.tools.find-usages/target-group"
            "mcp.output/oversized-first-finding: 1 usage"
            "src/mcp/output.clj:L:C  call  mcp.output/render"]
           (map #(st/replace % #":\d+:\d+  " ":L:C  ")
                (answer-lines (project/code-index)
                              {:targets ["mcp.output/definition->line"
                                         "output/oversized-first-finding"]}))))))

(deftest finds-plain-keyword-in-code-and-edn
  (is (= [":timeout: 5 usages"
          "resources/config.edn:1:2  edn"
          "resources/config.edn:3:11  edn"
          "src/kw/core.clj:7:12  keyword  kw.core/read-opts"
          "src/kw/core.clj:10:5  keyword  kw.core/read-opts"
          "src/kw/other.clj:7:4  keyword  kw.other/caller"]
         (answer-lines @keyword-project {:targets [":timeout"]}))))

(def ^:private uuid-usages
  [":kw.core/uuid: 4 usages"
   "resources/config.edn:2:2  edn"
   "src/kw/core.clj:8:5  keyword  kw.core/read-opts"
   "src/kw/core.clj:9:5  keyword  kw.core/read-opts"
   "src/kw/other.clj:6:4  keyword  kw.other/caller"])

(deftest auto-resolved-and-qualified-keywords-denote-one-target
  (testing "spec keyword, written qualified, auto-resolved or alias-qualified"
    (doseq [written [":kw.core/uuid" "::uuid" "::c/uuid"]]
      (is (= uuid-usages (answer-lines @keyword-project {:targets [written]})) written)))
  (testing "keyword without a spec"
    (is (= [":kw.other/local: 1 usage"
            "src/kw/other.clj:8:4  keyword  kw.other/caller"]
           (answer-lines @keyword-project {:targets ["::local"]})))
    (is (= "no usages of :nowhere/at-all (checked 3 files, including tests)"
           (answer @keyword-project {:targets [":nowhere/at-all"]})))
    (is (= "not found: ::nowhere (checked 3 files, including tests)"
           (answer @keyword-project {:targets ["::nowhere"]})))))

(deftest keywords-and-definitions-mix-in-one-call
  (let [lines (answer-lines @keyword-project {:targets [":timeout" "kw.core/read-opts"]})]
    (is (= ":timeout: 5 usages" (first lines)))
    (is (= "no usages of kw.core/read-opts (checked 3 files, including tests)" (last lines)))
    (is (= 7 (count lines)))))

(deftest kinds-separate-code-keywords-from-edn
  (is (= [":timeout: 2 usages"
          "resources/config.edn:1:2  edn"
          "resources/config.edn:3:11  edn"]
         (answer-lines @keyword-project {:targets [":timeout"] :kinds ["edn"]}))))

(deftest finds-keyword-usages-on-own-code-base
  (let [lines (answer-lines (project/code-index) {:targets [":output/max-chars"] :limit 50})]
    (is (re-find #"^:output/max-chars: \d+ usages$" (first lines)))
    (is (some #(re-matches #"resources/config\.edn:\d+:\d+  edn" %) lines))
    (is (some #(re-matches #"src/mcp/output\.clj:\d+:\d+  keyword  mcp\.output/.+" %) lines))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 4)
        text (answer cfg @lein-project {:targets ["get-uuid"]})
        next-page (answer cfg @lein-project {:targets ["get-uuid"] :offset 4})]
    (is (<= (count text) (:output/max-chars cfg)))
    (is (st/ends-with? text "truncated: shown 4 of 13, pass offset=4 or narrow the query"))
    (is (st/starts-with? next-page "app.core/get-uuid: 13 usages\n"))))

(deftest description-says-why-it-beats-grep
  (let [spec (some #(when (= "find_usages" (:name %)) %)
                   (tools/tool-definitions))]
    (is (re-find #"grep" (:description spec)))
    (is (re-find #"max-chars" (:description spec)))
    (is (= #{:targets :kinds :include_tests :limit :offset}
           (set (keys (get-in spec [:inputSchema :properties])))))))

(deftest old-caller-tools-are-gone
  (let [names (set (map :name (tools/tool-definitions)))]
    (is (contains? names "find_usages"))
    (is (not (contains? names "find_callers")))
    (is (not (contains? names "find_callees")))))
