(ns mcp.code-index-test
  "The precise index on this project's own code base (no Qdrant/Ollama)."
  (:require [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as sut]
            [mcp.test-project :as project])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn- located
  [defs]
  (set (map (juxt sut/qualified-name :row) defs)))

(deftest resolves-simple-alias-qualified-and-fully-qualified-names
  (let [idx (project/code-index)
        expected #{["mcp.output/relative-path" 25]}]
    (testing "simple name"
      (is (= expected (located (sut/resolve-name idx "relative-path")))))
    (testing "alias-qualified name, as written in mcp.tools"
      (is (= expected (located (sut/resolve-name idx "output/relative-path")))))
    (testing "fully qualified name"
      (is (= expected (located (sut/resolve-name idx "mcp.output/relative-path")))))))

(deftest resolves-a-namespace-name-to-its-ns-form
  (is (= #{["mcp.output" 1]}
         (located (sut/resolve-name (project/code-index) "mcp.output")))))

(deftest ambiguous-simple-name-resolves-to-every-candidate
  (is (= #{"mcp.qdrant-test/test-config"
           "mcp.search-test/test-config"
           "mcp.tools-test/test-config"}
         (set (map sut/qualified-name
                   (sut/resolve-name (project/code-index) "test-config"))))))

(deftest unknown-names-resolve-to-nothing
  (let [idx (project/code-index)]
    (is (empty? (sut/resolve-name idx "no-such-definition")))
    (is (empty? (sut/resolve-name idx "output/no-such-definition")))
    (is (empty? (sut/resolve-name idx "no.such.ns/relative-path")))
    (is (empty? (sut/resolve-name idx "")))))

;; ---------------------------------------------------------------------------
;; Definitions from tests, multimethods, specs and project macros
;; ---------------------------------------------------------------------------

(def ^:private fixture-root
  (.getCanonicalPath (File. "test-resources/fixtures/definitions_project")))

(def ^:private fixture-index
  (delay (sut/build (assoc project/config :index/root-path fixture-root))))

(defn- described
  [defs]
  (set (map (juxt sut/qualified-name :kind :row) defs)))

(deftest deftest-defmethod-spec-and-lint-as-macro-yield-definitions
  (let [defs (described (sut/definitions @fixture-index))]
    (is (contains? defs ["app.core-test/helper-test" "deftest" 5]))
    (is (contains? defs ["app.core/area" "defmulti" 11]))
    (is (contains? defs ["app.core/area :circle" "defmethod" 15]))
    (is (contains? defs ["app.core/area [:sq :rect]" "defmethod" 19]))
    (is (contains? defs [":app.core/uuid" "s/def" 23]))
    (is (contains? defs ["app.core/handle-uuid" "defhandler" 26]))))

(deftest defmethod-and-spec-definitions-span-their-whole-form
  (let [by-name (into {} (map (juxt sut/qualified-name identity))
                      (sut/definitions @fixture-index))]
    (is (= [15 1 17 22] ((juxt :row :col :end-row :end-col) (by-name "app.core/area :circle"))))
    (is (= [23 1 24 39] ((juxt :row :col :end-row :end-col) (by-name ":app.core/uuid"))))))

(deftest resolves-test-method-spec-and-macro-names
  (let [idx @fixture-index
        resolved #(described (sut/resolve-name idx %))]
    (is (= #{["app.core-test/helper-test" "deftest" 5]} (resolved "helper-test")))
    (is (= #{["app.core/handle-uuid" "defhandler" 26]} (resolved "handle-uuid")))
    (testing "a multimethod name denotes the defmulti, not its methods"
      (is (= #{["app.core/area" "defmulti" 11]} (resolved "area"))))
    (testing "a multimethod name with a dispatch value denotes that method"
      (is (= #{["app.core/area :circle" "defmethod" 15]} (resolved "area :circle")))
      (is (= #{["app.core/area [:sq :rect]" "defmethod" 19]} (resolved "core/area [:sq :rect]")))
      (is (= #{["app.core/area :circle" "defmethod" 15]} (resolved "app.core/area :circle"))))
    (testing "a spec keyword, fully qualified, auto-resolved or alias-qualified"
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved ":app.core/uuid")))
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved "::uuid")))
      (is (= #{[":app.core/uuid" "s/def" 23]} (resolved "::core/uuid")))
      (is (empty? (resolved ":other/uuid"))))))

(deftest enclosing-definition-of-usages-inside-these-forms
  (let [idx @fixture-index
        enclosing (fn [{:keys [filename name-row name-col]}]
                    (some-> (sut/enclosing-definition idx filename name-row name-col)
                            sut/qualified-name))]
    (is (= #{[17 "app.core/area :circle"]
             [24 ":app.core/uuid"]
             [29 "app.core/handle-uuid"]
             [33 "app.core/Circle"]
             [6 "app.core-test/helper-test"]}
           (->> (sut/entries idx :var-usages)
                (filter (comp #{'helper} :name))
                (filter :name-row)
                (map (juxt :name-row enclosing))
                set)))
    (testing "a position outside every Definition has none"
      (is (nil? (sut/enclosing-definition idx (str fixture-root "/src/app/core.clj") 5 1))))))

(deftest project-kondo-config-is-optional
  (let [idx (sut/build (assoc project/config :index/root-path
                              (.getCanonicalPath (File. "test-resources/fixtures/multi_ns"))))]
    (is (seq (sut/definitions idx)))))

(deftest indexes-source-and-test-files-but-not-excluded-ones
  (let [idx (project/code-index)
        files (set (map #(subs % (inc (count project/root))) (sut/files idx)))]
    (is (contains? files "src/mcp/output.clj"))
    (is (contains? files "test/mcp/output_test.clj"))
    (is (contains? files "project.clj"))
    (is (contains? files "resources/config.edn"))
    (is (not-any? #(re-find #"^test-resources/" %) files))
    (is (sut/test-file? idx (str project/root "/test/mcp/output_test.clj")))
    (is (not (sut/test-file? idx (str project/root "/src/mcp/output.clj"))))))
