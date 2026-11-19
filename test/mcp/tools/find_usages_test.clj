(ns mcp.tools.find-usages-test
  "Acceptance cases 1 (without `redef`/`in-comment`) and 2 of the spec, on
   fixture projects under `test-resources/usages/` and on this project's
   own code base."
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn- fixture-index
  [project-dir]
  (code-index/build
   (assoc project/config :index/root-path
          (.getCanonicalPath (File. (str "test-resources/usages/" project-dir))))))

(def ^:private lein-project (delay (fixture-index "lein_project")))
(def ^:private deps-project (delay (fixture-index "deps_project")))
(def ^:private plain-project (delay (fixture-index "plain_project")))

(defn- answer
  ([code-index args] (answer project/config code-index args))
  ([cfg code-index args]
   (-> (tools/handle-tool "find_usages" args {:config cfg :code-index code-index})
       :content first :text)))

(defn- answer-lines
  [code-index args]
  (s/split-lines (answer code-index args)))

(def ^:private get-uuid-usages
  ["src/app/core.clj:10:11  value  app.core/uses-value"
   "src/app/core.clj:11:15  value  app.core/uses-value"
   "src/app/core.clj:12:16  value  app.core/uses-value"
   "src/app/core.clj:13:11  value  app.core/uses-value"
   "spec/app/core_spec.clj:6:4  call  app.core-spec/check-uuid"
   "src/app/other.clj:2:37  require  app.other"
   "src/app/other.clj:6:5  call  app.other/caller"
   "src/app/other.clj:7:5  call  app.other/caller"
   "src/app/other.clj:8:5  call  app.other/caller"
   "src/app/other.clj:9:12  var-ref  app.other/caller"
   "src/app/other.clj:10:9  var-ref  app.other/caller"])

(deftest finds-usages-through-alias-refer-var-and-value-passing
  (let [[header & usages] (answer-lines @lein-project {:targets ["get-uuid"]})]
    (is (= "app.core/get-uuid: 11 usages" header))
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
    (is (= "app.core/get-uuid: 11 usages" (first uuid-group)))
    (is (= 12 (count uuid-group)))
    (is (= ["app.core/uses-value: 1 usage"
            "src/app/other.clj:11:5  call  app.other/caller"]
           uses-value-group))))

(deftest kinds-filter-the-usages
  (is (= ["app.core/get-uuid: 2 usages"
          "src/app/other.clj:9:12  var-ref  app.other/caller"
          "src/app/other.clj:10:9  var-ref  app.other/caller"]
         (answer-lines @lein-project {:targets ["get-uuid"] :kinds ["var-ref"]}))))

(deftest test-usages-are-dropped-when-tests-are-excluded
  (testing "Test path from project.clj :test-paths"
    (is (not-any? #(s/starts-with? % "spec/")
                  (answer-lines @lein-project {:targets ["get-uuid"] :include_tests false})))
    (is (= "app.core/get-uuid: 10 usages"
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
    (is (= "no usages of app.core-spec/check-uuid (checked 4 files, including tests)"
           (answer @lein-project {:targets ["check-uuid"]}))))
  (testing "excluding tests"
    (is (= "no usages of app.core/uses-value (checked 3 files, excluding tests)"
           (answer @lein-project {:targets ["uses-value"] :kinds ["value"] :include_tests false})))))

(deftest unknown-and-ambiguous-targets
  (is (= "not found: no-such-fn (checked 4 files, including tests)"
         (answer @lein-project {:targets ["no-such-fn"]})))
  (is (= "ambiguous: check is any of plain.b/check, plain.c-test/check, pass a qualified name"
         (answer @plain-project {:targets ["check"]}))))

(deftest finds-usages-on-own-code-base
  (testing (str "line and column numbers are left out: they move with every edit; "
                "same-named private definition->line fns of other tools are not Usages")
    (is (= ["mcp.tools.find-definition/definition->line: 2 usages"
            "src/mcp/tools/find_definition.clj:L:C  value  mcp.tool/call :find_definition"
            "src/mcp/tools/get_source.clj:L:C  value  mcp.tool/call :get_source"
            "mcp.output/oversized-first-finding: 1 usage"
            "src/mcp/output.clj:L:C  call  mcp.output/render"]
           (map #(s/replace % #":\d+:\d+  " ":L:C  ")
                (answer-lines (project/code-index)
                              {:targets ["mcp.tools.find-definition/definition->line"
                                         "output/oversized-first-finding"]}))))))

(deftest answer-respects-output-budget
  (let [cfg (assoc project/config :output/limit 4)
        text (answer cfg @lein-project {:targets ["get-uuid"]})
        next-page (answer cfg @lein-project {:targets ["get-uuid"] :offset 4})]
    (is (<= (count text) (:output/max-chars cfg)))
    (is (s/ends-with? text "truncated: shown 4 of 11, pass offset=4 or narrow the query"))
    (is (s/starts-with? next-page "app.core/get-uuid: 11 usages\n"))))

(deftest description-says-why-it-beats-grep
  (let [spec (some #(when (= "find_usages" (:name %)) %)
                   (:tools (tools/handle-tools-list {})))]
    (is (re-find #"grep" (:description spec)))
    (is (re-find #"max-chars" (:description spec)))
    (is (= #{:targets :kinds :include_tests :limit :offset}
           (set (keys (get-in spec [:inputSchema :properties])))))))

(deftest old-caller-tools-are-gone
  (let [names (set (map :name (:tools (tools/handle-tools-list {}))))]
    (is (contains? names "find_usages"))
    (is (not (contains? names "find_callers")))
    (is (not (contains? names "find_callees")))))
