(ns mcp.tools.find-implementations-test
  "`find_implementations` on a fixture project covering every form of
   Implementation."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private fixture-config
  (assoc project/config :index/root-path
         (.getCanonicalPath (File. "test-resources/fixtures/implementations_project"))))

(def ^:private fixture-index
  (delay (code-index/build fixture-config)))

(defn- answer
  "The answer text of `find_implementations` on the fixture project.

  ToolArgs -> IO String
  Config -> ToolArgs -> IO String"
  ([args] (answer fixture-config args))
  ([cfg args]
   (project/answer-text cfg
                        (tool/call :find_implementations args
                                   {:config cfg :code-index @fixture-index}))))

(deftest lists-protocol-implementations-with-implementing-type-and-method
  (let [expected ["src/impl/other.clj:8  impl.other/Triangle  area"
                  "src/impl/shapes.clj:10  impl.shapes/Circle  area"
                  "src/impl/shapes.clj:11  impl.shapes/Circle  perimeter"
                  "src/impl/shapes.clj:15  impl.shapes/Square  area"
                  "src/impl/shapes.clj:19  String  area"
                  "src/impl/shapes.clj:23  nil  area"
                  "src/impl/shapes.clj:25  Long  area"
                  "src/impl/shapes.clj:26  Long  perimeter"
                  "src/impl/shapes.clj:31  reify in impl.shapes/make-shape  area"]]
    (testing "simple name"
      (is (= expected (st/split-lines (answer {:name "Shape"})))))
    (testing "fully qualified name"
      (is (= expected (st/split-lines (answer {:name "impl.shapes/Shape"})))))
    (testing "alias-qualified name, as written in impl.other"
      (is (= expected (st/split-lines (answer {:name "sh/Shape"})))))))

(deftest lists-multimethod-implementations-with-dispatch-value
  (is (= ["src/impl/other.clj:10  :triangle  render"
          "src/impl/shapes.clj:35  :circle  render"
          "src/impl/shapes.clj:37  [:sq :rect]  render"]
         (st/split-lines (answer {:name "render"})))))

(deftest name-that-is-neither-protocol-nor-multimethod-says-so
  (is (= "not a protocol or multimethod: make-shape (impl.shapes/make-shape defn)"
         (answer {:name "make-shape"}))))

(deftest ambiguous-candidates-show-their-type-signatures
  (is (= (str "ambiguous: 2 protocols or multimethods named paint, pass a qualified name\n"
              "src/impl/other.clj:12  defmulti  impl.other/paint  Figure -> IO ()  "
              "Paints a figure in another way.\n"
              "src/impl/shapes.clj:42  defmulti  impl.shapes/paint  Shape -> IO ()  Paints a shape.")
         (answer {:name "paint"}))))

(deftest miss-reports-what-was-searched
  (testing "unknown name"
    (is (= "not found: Nope (checked 2 files, including tests)"
           (answer {:name "Nope"}))))
  (testing "protocol nobody implements"
    (is (= "no implementations of impl.shapes/Unused (checked 2 files, including tests)"
           (answer {:name "Unused"})))))

(deftest answer-respects-output-budget
  (let [cfg (assoc fixture-config :output/limit 2)
        text (answer cfg {:name "Shape"})]
    (is (= 3 (count (st/split-lines text))))
    (is (st/ends-with? text "truncated: shown 2 of 9, pass offset=2 or narrow the query"))
    (is (st/starts-with? (answer cfg {:name "Shape" :offset 2})
                         "src/impl/shapes.clj:11  impl.shapes/Circle  perimeter"))
    (is (<= (count text) (:output/max-chars cfg)))))

(deftest replaces-old-protocol-record-and-macro-tools
  (let [tool-specs (tools/tool-definitions)
        names (set (map :name tool-specs))
        spec (some #(when (= "find_implementations" (:name %)) %) tool-specs)]
    (is (contains? names "find_implementations"))
    (is (empty? (filter #{"find_protocol" "find_record" "find_macro"} names)))
    (is (re-find #"grep" (:description spec)))
    (is (= #{:name :ns :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))))

;; ---------------------------------------------------------------------------
;; Names as written in a namespace
;; ---------------------------------------------------------------------------

(defn- ns-answer
  "The answer text of `find_implementations` on the namespaces fixture.

  ToolArgs -> IO String"
  [args]
  (project/answer-text project/config
                       (tool/call :find_implementations args
                                  {:config project/config :code-index (project/namespaces-index)})))

(deftest name-resolves-as-written-in-the-given-namespace
  (testing "one alias for different protocols"
    (is (= "src/ns_app/one.clj:11  ns-app.one/OneStore  save"
           (ns-answer {:name "store/Store" :ns "ns-app.one"})))
    (is (= "src/ns_app/two.clj:11  ns-app.two/TwoStore  save"
           (ns-answer {:name "store/Store" :ns "ns-app.two"}))))
  (testing "one alias for different multimethods"
    (is (= "src/ns_app/two.clj:13  :two  handle"
           (ns-answer {:name "store/handle" :ns "ns-app.two"}))))
  (testing "a bare name of the namespace's own Definition"
    (is (= "src/ns_app/one.clj:13  :one  handle"
           (ns-answer {:name "handle" :ns "ns-app.alpha"})))))

(deftest name-not-resolvable-in-the-namespace-lists-all-candidates
  (is (= (str "not resolvable in ns-app.one, all candidates:\n"
              "ambiguous: 2 protocols or multimethods named Store, pass a qualified name\n"
              "src/ns_app/alpha.clj:12  defprotocol  ns-app.alpha/Store\n"
              "src/ns_app/beta.clj:12  defprotocol  ns-app.beta/Store")
         (ns-answer {:name "Store" :ns "ns-app.one"}))))

(deftest unknown-namespace-answers-only-that
  (is (= "unknown namespace: ns-app.nope" (ns-answer {:name "Store" :ns "ns-app.nope"}))))
