(ns mcp.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.server :as sut]
            [mcp.config :as config]
            [mcp.semantic-index :as semantic-index]
            [mcp.test-project :as project])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

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
    (is (= "2025-06-18" (get-in response [:result :protocolVersion])))
    (is (= "mcp-clojure-index" (get-in response [:result :serverInfo :name])))
    (is (get-in response [:result :capabilities :tools]))
    (is (= state {:config config/defaults}))))

(deftest initialize-returns-instructions-to-prefer-the-server-over-grep
  (let [[response _state] (#'sut/initialize-handler {:id 1 :method "initialize"} {})
        instructions (get-in response [:result :instructions])]
    (is (<= 4 (count (st/split-lines instructions)) 6))
    (doseq [phrase ["instead of grep" ":refer" "#'var" "with-redefs" "keywords in EDN"
                    "path:line" "size ceiling" "get_source"
                    "re-read from changed files" "edits are visible immediately"]]
      (is (st/includes? instructions phrase) phrase))
    (testing "says only what the spec lists"
      (is (not (st/includes? instructions "find_definition"))))))

(deftest handle-method-tools-call-find-definition-without-qdrant
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name "find_definition" :arguments {:names ["mcp.output/relative-path"]}}}
        state {:config project/config :code-index-ref (atom (project/code-index))}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= [{:type "text"
             :text "mcp.output/relative-path\nsrc/mcp/output.clj:60  defn  mcp.output/relative-path  [root-path file-path]  Maybe String -> String -> String  Returns `file-path` relative to the project root `root-path`; paths outside the root are returned unchanged."}]
           (get-in response [:result :content])))))

(def ^:private services-down-config
  (assoc project/config
         :qdrant/port 19999
         :ollama/url "http://localhost:19999/v1"))

(defn- tool-call
  "The answer text of a `tools/call` of `tool-name` with `arguments`,
   asserting that it fits the Output budget.

  ToolState -> String -> ToolArgs -> IO String"
  [state tool-name arguments]
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name tool-name :arguments arguments}}]
    (project/answer-text (:config state) (-> (#'sut/handle-method msg state) first :result))))

(def ^:private relative-path-definition
  (str "src/mcp/output.clj:60  defn  mcp.output/relative-path  [root-path file-path]  "
       "Maybe String -> String -> String  "
       "Returns `file-path` relative to the project root `root-path`; paths outside the root "
       "are returned unchanged."))

(deftest with-qdrant-and-ollama-down-precise-tools-answer-and-semantic-search-says-unavailable
  (let [state {:config services-down-config
               :code-index-ref (atom (project/code-index))
               :semantic-index (semantic-index/create services-down-config)}]
    (is (= (str "mcp.output/relative-path\n" relative-path-definition)
           (tool-call state "find_definition" {:names ["mcp.output/relative-path"]})))
    (is (= (str "semantic search unavailable (Qdrant or Ollama not reachable); "
                "the precise tools (find_definition, get_source, ...) still work")
           (tool-call state "semantic_search" {:query "first line of a docstring"})))))

(deftest freshness-check-hands-the-refreshed-index-to-the-embeddings-sync
  (let [synced (atom nil)
        semantic-index (semantic-index/create services-down-config)
        state {:config services-down-config
               :code-index-ref (atom (project/code-index))
               :semantic-index semantic-index}]
    (with-redefs [semantic-index/sync-async! (fn [synced-index index]
                                               (reset! synced [synced-index index]))]
      (tool-call state "find_definition" {:names ["mcp.output/relative-path"]}))
    (is (= [semantic-index @(:code-index-ref state)] @synced))))

(defn- find-definition-call
  "The answer text of `find_definition` for `name-str`.

  ToolState -> String -> IO String"
  [state name-str]
  (tool-call state "find_definition" {:names [name-str]}))

(deftest tools-call-answer-reflects-an-edit-made-just-before-it
  (let [root (.getCanonicalPath (.toFile (Files/createTempDirectory "server-test" (make-array FileAttribute 0))))
        file (io/file root "src/app/core.clj")
        cfg (assoc config/defaults :index/root-path root)
        _ (io/make-parents file)
        _ (spit file "(ns app.core)\n(defn alpha [x] x)\n")
        state {:config cfg :code-index-ref (atom (code-index/build cfg))}]
    (is (= "alpha\nsrc/app/core.clj:2  defn  app.core/alpha  [x]" (find-definition-call state "alpha")))
    (spit file "(ns app.core)\n\n(defn beta [y] y)\n")
    (.setLastModified file (+ 2000 (System/currentTimeMillis)))
    (is (= "beta\nsrc/app/core.clj:3  defn  app.core/beta  [y]" (find-definition-call state "beta")))
    (is (= "alpha: not found (checked 1 files, including tests)"
           (find-definition-call state "alpha")))))

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
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)
        tool-names (set (map :name (get-in response [:result :tools])))]
    (is (= 1 (:id response)))
    (is (contains? tool-names "semantic_search"))
    (is (contains? tool-names "find_definition"))
    (is (not (contains? tool-names "find_symbol")))
    (is (not (contains? tool-names "find_namespace")))
    (is (contains? tool-names "find_usages"))
    (is (not (contains? tool-names "find_callers")))
    (is (contains? tool-names "find_implementations"))
    (is (not (contains? tool-names "find_protocol")))))

(deftest handle-method-tools-call-invalid-tool
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name "nonexistent_tool" :arguments {}}}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (get-in response [:error]))))

(deftest process-message-valid
  (let [line "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
        state {:config config/defaults}
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
  (let [state {:config config/defaults}
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

;; ---------------------------------------------------------------------------
;; Edge cases: server error handling
;; ---------------------------------------------------------------------------

(deftest handle-method-missing-params
  (let [msg {:jsonrpc "2.0" :id 1 :method "tools/call"}
        state {:config config/defaults}
        [response _new-state] (#'sut/handle-method msg state)]
    (is (= 1 (:id response)))
    (is (= -32602 (get-in response [:error :code])))))

(deftest json-rpc-error-with-nil-id
  (let [result (#'sut/json-rpc-error nil -32601 "Method not found")]
    (is (= "2.0" (:jsonrpc result)))
    (is (= nil (:id result)))
    (is (= -32601 (get-in result [:error :code])))))

(deftest json-rpc-result-with-nil-id
  (let [result (#'sut/json-rpc-result nil {:tools []})]
    (is (= "2.0" (:jsonrpc result)))
    (is (= nil (:id result)))
    (is (= [] (get-in result [:result :tools])))))

;; ---------------------------------------------------------------------------
;; Edge cases: parse-json with edge inputs
;; ---------------------------------------------------------------------------

(deftest parse-json-empty-object
  (let [result (#'sut/parse-json "{}")]
    (is (map? result))
    (is (empty? result))))

(deftest parse-json-whitespace-only
  (is (nil? (#'sut/parse-json "   "))))

(deftest parse-json-unicode
  (let [result (#'sut/parse-json "{\"key\":\"value\"}")]
    (is (= "value" (:key result)))))

;; ---------------------------------------------------------------------------
;; Edge cases: process-message with various inputs
;; ---------------------------------------------------------------------------

(deftest process-message-tools-call
  (let [state {:config project/config :code-index-ref (atom (project/code-index))}
        line "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"find_definition\",\"arguments\":{\"names\":[\"test\"]}}}"
        [response _new-state] (#'sut/process-message line state)]
    (is (some? response))
    (is (= 1 (:id response)))
    (is (get-in response [:result :content]))))

(deftest process-message-not-allowed-method
  (let [state {:config config/defaults}
        line "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}"
        [response _new-state] (#'sut/process-message line state)]
    (is (some? response))
    (is (= -32601 (get-in response [:error :code])))))

(deftest live-state-returns-the-current-code-index
  (let [state {:config config/defaults
               :code-index-ref (atom {:files {}})}]
    (is (= {:files {}} (:code-index (#'sut/live-state state))))))
