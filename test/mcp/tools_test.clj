(ns mcp.tools-test
  (:require [clojure.test :refer [deftest is]]
            [mcp.tools :as sut]
            [mcp.config :as config]))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-tools"
         :search/top-k 100
         :search/re-rank-top 10))

(deftest tool-definitions-contain-all-tools
  (let [names (set (map :name (sut/tool-definitions)))]
    (is (contains? names "semantic_search"))
    (is (contains? names "find_definition"))
    (is (contains? names "get_source"))
    (is (contains? names "find_usages"))
    (is (not (contains? names "find_callers")))
    (is (contains? names "find_implementations"))
    (is (not (contains? names "find_protocol")))
    (is (contains? names "namespace_summary"))))

(deftest tool-definitions-have-required-fields
  (doseq [t (sut/tool-definitions)]
    (is (contains? t :name))
    (is (contains? t :description))
    (is (contains? t :inputSchema))
    (let [schema (:inputSchema t)]
      (is (= "object" (:type schema))))))

