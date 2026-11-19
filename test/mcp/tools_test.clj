(ns mcp.tools-test
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is]]
            [mcp.tools :as sut]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.config :as config])
  (:import (java.util UUID)))

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
    (is (contains? names "find_callers"))
    (is (contains? names "find_callees"))
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

(deftest handle-tools-list-returns-all-tools
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tools-list state)
        tool-names (set (map :name (:tools result)))]
    (is (contains? tool-names "semantic_search"))
    (is (contains? tool-names "find_definition"))
    (is (contains? tool-names "find_callers"))
    (is (contains? tool-names "find_callees"))
    (is (contains? tool-names "find_implementations"))
    (is (not (contains? tool-names "find_protocol")))))

(deftest find-callers-found
  (let [gr (-> (graph/empty-graph)
               (graph/add-file!
                [{:edge/from 'test.ns/caller :edge/from-id (UUID/randomUUID)
                  :edge/to 'test.ns/target :edge/to-id (UUID/randomUUID)
                  :edge/type :call :edge/file "/f.clj" :edge/line 1}]))
        state {:config test-config :index (si/empty-index) :graph-state gr}
        result (sut/handle-tool "find_callers" {:symbol "test.ns/target"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "test.ns/caller"))))

(deftest find-callers-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callers" {:symbol "test.ns/unknown"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callers"))))

(deftest find-callees-found
  (let [gr (-> (graph/empty-graph)
               (graph/add-file!
                [{:edge/from 'test.ns/main :edge/from-id (UUID/randomUUID)
                  :edge/to 'test.ns/util :edge/to-id (UUID/randomUUID)
                  :edge/type :call :edge/file "/f.clj" :edge/line 1}]))
        state {:config test-config :index (si/empty-index) :graph-state gr}
        result (sut/handle-tool "find_callees" {:symbol "test.ns/main"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "test.ns/util"))))

(deftest find-callees-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callees" {:symbol "test.ns/unknown"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callees"))))

;; ---------------------------------------------------------------------------
;; Edge cases: tools with nil/empty arguments
;; ---------------------------------------------------------------------------

(deftest find-callers-with-nil-symbol
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callers" {:symbol nil} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callers"))))

(deftest find-callees-with-nil-symbol
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callees" {:symbol nil} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callees"))))
