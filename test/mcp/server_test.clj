(ns mcp.server-test
  (:require [clojure.test :refer :all]
            [mcp.server :as sut]
            [mcp.config :as config]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(deftest json-rpc-error-format
  (let [result (#'sut/json-rpc-error 1 -32601 "Method not found")]
    (is (= "2.0" (:jsonrpc result)))
    (is (= 1 (:id result)))
    (is (= -32601 (get-in result [:error :code])))
    (is (= "Method not found" (get-in result [:error :message])))))

(deftest json-rpc-result-format
  (let [result (#'sut/json-rpc-result 1 {:tools []})]
    (is (= "2.0" (:jsonrpc result)))
    (is (= 1 (:id result)))
    (is (= [] (get-in result [:result :tools])))))

(deftest parse-json-valid
  (let [result (#'sut/parse-json "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test\"}")]
    (is (= "2.0" (:jsonrpc result)))
    (is (= 1 (:id result)))
    (is (= "test" (:method result)))))

(deftest parse-json-invalid
  (is (nil? (#'sut/parse-json "not json")))
  (is (nil? (#'sut/parse-json "")))
  (is (nil? (#'sut/parse-json nil))))

(deftest initialize-handler-returns-server-info
  (let [msg {:id 1 :method "initialize" :params {:clientInfo {:name "test" :version "1.0"}}}
        state {:config config/defaults}
        [response _new-state] (#'sut/initialize-handler msg state)]
    (is (= 1 (:id response)))
    (is (= "2024-11-05" (get-in response [:result :protocolVersion])))
    (is (= "mcp-clojure-index" (get-in response [:result :serverInfo :name])))
    (is (get-in response [:result :capabilities :tools]))
    (is (= state {:config config/defaults}))))

(deftest handle-method-initialize
  (let [msg {:jsonrpc "2.0" :id 1 :method "initialize" :params {:clientInfo {:name "test" :version "1.0"}}}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (get-in response [:result :serverInfo]))))

(deftest handle-method-unknown-returns-error
  (let [msg {:jsonrpc "2.0" :id 1 :method "unknown_method"}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (= -32601 (get-in response [:error :code])))))

(deftest handle-method-tools-list
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/list"}
        state {:config config/defaults
               :index (si/empty-index)
               :graph-state (graph/empty-graph)}
        [response _new-state] (#'sut/handle-method msg state)
        tool-names (set (map :name (get-in response [:result :tools])))]
    (is (= 1 (:id response)))
    (is (contains? tool-names "semantic_search"))
    (is (contains? tool-names "find_symbol"))
    (is (contains? tool-names "find_namespace"))
    (is (contains? tool-names "find_callers"))
    (is (contains? tool-names "find_callees"))
    (is (contains? tool-names "find_protocol"))
    (is (contains? tool-names "find_record"))
    (is (contains? tool-names "find_macro"))))

(deftest handle-method-tools-call-find-symbol
  (let [cid (UUID/randomUUID)
        sym {:sym/name 'test.ns/my-func :sym/simple 'my-func :sym/ns 'test.ns
             :sym/type :fn :sym/file "/f.clj" :sym/line 1
             :sym/chunk-id cid :sym/visibility :public :sym/aliases #{}}
        index (si/add-file! (si/empty-index) [sym])
        msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name "find_symbol" :arguments {:name "test.ns/my-func"}}}
        state {:config config/defaults :index index :graph-state (graph/empty-graph)}
        [response _new-state] (#'sut/handle-method msg state)
        content (get-in response [:result :content])]
    (is (= 1 (:id response)))
    (is (some? content))
    (is (some #(re-find #"test.ns/my-func" (:text %)) content))))

(deftest handle-method-tools-call-find-symbol-not-found
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name "find_symbol" :arguments {:name "nonexistent"}}}
        state {:config config/defaults :index (si/empty-index) :graph-state (graph/empty-graph)}
        [response _new-state] (#'sut/handle-method msg state)
        content (get-in response [:result :content])]
    (is (= 1 (:id response)))
    (is (some #(re-find #"not found" (:text %)) content))))

(deftest handle-method-tools-call-invalid-tool
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name "nonexistent_tool" :arguments {}}}
        state {:config config/defaults :index (si/empty-index) :graph-state (graph/empty-graph)}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (get-in response [:error]))))

(deftest process-message-valid
  (let [line "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
        state {:config config/defaults :index (si/empty-index) :graph-state (graph/empty-graph)}
        [response _new-state] (#'sut/process-message line state)]
    (is (some? response))
    (is (= 1 (:id response)))
    (is (get-in response [:result :tools]))))

(deftest process-message-invalid-json
  (let [[response state] (#'sut/process-message "not json" {})]
    (is (nil? response))
    (is (= {} state))))

(deftest process-message-empty-line
  (let [[response state] (#'sut/process-message "" {})]
    (is (nil? response))
    (is (= {} state))))

(deftest handle-method-notifications-initialized
  (let [msg {:jsonrpc "2.0" :method "notifications/initialized"}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (nil? response))
    (is (= state _new-state))))

(deftest handle-method-tools-call-missing-tool-name
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:arguments {}}}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (= -32602 (get-in response [:error :code])))
    (is (re-find #"tool name" (get-in response [:error :message])))))

(deftest handle-method-tools-call-nil-tool-name
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name nil :arguments {}}}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (= -32602 (get-in response [:error :code])))))

(deftest process-message-full-cycle
  (let [state {:config config/defaults :index (si/empty-index) :graph-state (graph/empty-graph)}
        init-line "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}"
        list-line "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
        [init-resp state1] (#'sut/process-message init-line state)
        [list-resp _state2] (#'sut/process-message list-line state1)]
    (is (some? init-resp))
    (is (= "mcp-clojure-index" (get-in init-resp [:result :serverInfo :name])))
    (is (some? list-resp))
    (is (some #(= "semantic_search" (:name %)) (get-in list-resp [:result :tools])))))

(deftest stop-when-no-state
  (is (nil? (sut/stop))))