(ns mcp.tools.find-implementations-test
  "`find_implementations` on a fixture project covering every form of
   Implementation."
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.test-project :as project]
            [mcp.tools :as tools])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private fixture-config
  (assoc project/config :index/root-path
         (.getCanonicalPath (File. "test-resources/fixtures/implementations_project"))))

(def ^:private fixture-index
  (delay (code-index/build fixture-config)))

(defn- answer
  ([args] (answer fixture-config args))
  ([cfg args]
   (-> (tools/handle-tool "find_implementations" args
                          {:config cfg :code-index @fixture-index})
       :content first :text)))

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
      (is (= expected (s/split-lines (answer {:name "Shape"})))))
    (testing "fully qualified name"
      (is (= expected (s/split-lines (answer {:name "impl.shapes/Shape"})))))
    (testing "alias-qualified name, as written in impl.other"
      (is (= expected (s/split-lines (answer {:name "sh/Shape"})))))))

(deftest lists-multimethod-implementations-with-dispatch-value
  (is (= ["src/impl/other.clj:10  :triangle  render"
          "src/impl/shapes.clj:35  :circle  render"
          "src/impl/shapes.clj:37  [:sq :rect]  render"]
         (s/split-lines (answer {:name "render"})))))

(deftest name-that-is-neither-protocol-nor-multimethod-says-so
  (is (= "not a protocol or multimethod: make-shape (impl.shapes/make-shape defn)"
         (answer {:name "make-shape"}))))

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
    (is (= 3 (count (s/split-lines text))))
    (is (s/ends-with? text "truncated: shown 2 of 9, pass offset=2 or narrow the query"))
    (is (s/starts-with? (answer cfg {:name "Shape" :offset 2})
                        "src/impl/shapes.clj:11  impl.shapes/Circle  perimeter"))
    (is (<= (count text) (:output/max-chars cfg)))))

(deftest replaces-old-protocol-record-and-macro-tools
  (let [tool-specs (tools/tool-definitions)
        names (set (map :name tool-specs))
        spec (some #(when (= "find_implementations" (:name %)) %) tool-specs)]
    (is (contains? names "find_implementations"))
    (is (empty? (filter #{"find_protocol" "find_record" "find_macro"} names)))
    (is (re-find #"grep" (:description spec)))
    (is (= #{:name :limit :offset} (set (keys (get-in spec [:inputSchema :properties])))))))
