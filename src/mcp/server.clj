(ns mcp.server
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.semantic-index :as semantic-index]
            [mcp.tool :as tool]
            [mcp.tools :as tools])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)))

(set! *warn-on-reflection* true)

(def ^:private protocol-version
  "First MCP revision whose `initialize` result carries `instructions`
   is 2025-03-26; this is the latest one the server follows.

  String"
  "2025-06-18")

(def ^:private instructions
  (str "Use this server instead of grep and reading files for questions about this Clojure code base.\n"
       "It resolves names like the compiler: aliases, :refer, #'var, with-redefs, functions passed as values, keywords in EDN.\n"
       "Answers are compact `path:line` lines under a hard size ceiling; page with `offset` when an answer says it is truncated.\n"
       "Use get_source for a definition's body instead of reading the whole file."))
(defonce ^:private server-state-atom (atom nil))

;; JsonRpcMessage: a parsed request or notification, keyword keys.
;; JsonRpcResponse: what is written back for a request.
;; ServerState: the config, an atom of the code index and the semantic
;; index state; `live-state` turns it into a ToolState.

(s/def ::json-rpc-message (s/keys :req-un [::method] :opt-un [::jsonrpc ::id ::params]))

(s/def ::json-rpc-response (s/keys :req-un [::jsonrpc ::id] :opt-un [::result ::error]))

(s/def ::server-state
  (s/keys :req-un [:mcp.config/config]
          :opt-un [::code-index-ref ::semantic-index]))

(defn- json-rpc-error
  "The JSON-RPC error response to the request `id`.

  Maybe (String | Long) -> Long -> String -> JsonRpcResponse"
  [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code :message message}})

(defn- json-rpc-result
  "The JSON-RPC result response to the request `id`.

  Maybe (String | Long) -> Map -> JsonRpcResponse"
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn- parse-json
  "Parses one line of input as a JSON-RPC message; nil when it is not JSON.

  String -> Maybe JsonRpcMessage"
  [^String json-str]
  (try (json/read-str json-str :key-fn keyword)
       (catch Exception _ nil)))

(defn- write-json
  "Writes `data` as one line of JSON to `writer` and flushes it.

  OutputStreamWriter -> JsonRpcResponse -> IO ()"
  [^OutputStreamWriter writer data]
  (let [s (json/write-str data)]
    (.write writer s)
    (.write writer "\n")
    (.flush writer)))

(defn- initialize-handler
  "Answers `initialize` with the server's capabilities and `instructions`.

  JsonRpcMessage -> ServerState -> [JsonRpcResponse ServerState]"
  [msg state]
  (let [id (:id msg)
        server-info {:name "mcp-clojure-index"
                     :version "0.1.0"}]
    [(json-rpc-result id {:protocolVersion protocol-version
                          :capabilities {:tools {:listChanged false}}
                          :serverInfo server-info
                          :instructions instructions})
     state]))

(defn- live-state
  "The ToolState of `state`, with the current code index.

  ServerState -> IO ToolState"
  [state]
  (if-let [code-index-ref (:code-index-ref state)]
    (assoc state :code-index @code-index-ref)
    state))

(defn- ensure-fresh-code-index!
  "Re-analyses files changed on disk since the last answer, synchronously,
   so a precise tool never answers from a stale index (ADR-0005), then lets
   the embeddings of changed files catch up in the background.

  ServerState -> IO ServerState"
  [state]
  (when-let [code-index-ref (:code-index-ref state)]
    (try
      (let [index (swap! code-index-ref (partial code-index/refresh (:config state)))]
        (when-let [semantic-state (:semantic-index state)]
          (semantic-index/sync-async! (:config state) semantic-state index)))
      (catch Exception e
        (log/error e "Freshness check failed, answering from the last index"))))
  state)

(defn- handle-method
  "Answers one JSON-RPC message; nil for a notification.

  JsonRpcMessage -> ServerState -> IO [Maybe JsonRpcResponse, ServerState]"
  [msg state]
  (let [method (:method msg)
        id (:id msg)
        params (:params msg)
        fresh (live-state (cond-> state
                            (= "tools/call" method) ensure-fresh-code-index!))]
    (try
      (case method
        "initialize" (initialize-handler msg state)
        "notifications/initialized" [nil state]
        "tools/list"
        (let [result {:tools (tools/tool-definitions)}]
          [(json-rpc-result id result) state])
        "tools/call"
        (if-let [tool-name (get params :name)]
          (let [arguments (get params :arguments {})
                tool-result (tool/call (keyword tool-name) arguments fresh)]
            [(json-rpc-result id {:content (:content tool-result)}) state])
          [(json-rpc-error id -32602 "Missing tool name") state])
        [(json-rpc-error id -32601 (str "Method not found: " method)) state])
      (catch Exception e
        [(json-rpc-error id -32603 (.getMessage e)) state]))))

(defn stop
  "Forgets the running server's state.

  () -> IO ()"
  []
  (when @server-state-atom
    (reset! server-state-atom nil)
    (log/info "Server stopped")))

(defn- process-message
  "Answers one line of input; nil for a line that is not JSON.

  String -> ServerState -> IO [Maybe JsonRpcResponse, ServerState]"
  [^String line state]
  (let [msg (parse-json line)]
    (if (nil? msg)
      [nil state]
      (handle-method msg state))))

(defn start
  "Builds the code index and serves MCP on stdio until the input ends.

  Config -> IO ()"
  [cfg]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (OutputStreamWriter. System/out)
        _ (log/info "Starting MCP server with" (:server/transport cfg) "transport")
        code-index-ref (atom (code-index/build cfg))
        _ (log/info "Code index built:" (count (code-index/files @code-index-ref)) "files")
        semantic-state (semantic-index/make-state)
        server-state (atom {:config cfg
                            :code-index-ref code-index-ref
                            :semantic-index semantic-state})]
    (reset! server-state-atom @server-state)
    (semantic-index/sync-async! cfg semantic-state @code-index-ref)
    (log/info "MCP server ready on stdio (embeddings are computed in the background)")
    (try
      (loop [line (.readLine reader)]
        (when line
          (let [[response new-server-state] (process-message line @server-state)]
            (when response
              (write-json writer response))
            (when new-server-state
              (reset! server-state new-server-state))
            (recur (.readLine reader)))))
      (catch Exception e
        (log/error e "Error:" (.getMessage e))
        (log/warn "Stopping..."))
      (finally
        (stop)))))

(defn -main
  "Starts the server with the config file given as the first argument, or
   `resources/config.edn`.

  & String -> IO ()"
  [& args]
  (let [config-path (or (first args) "resources/config.edn")
        cfg (-> (config/load-config config-path)
                (config/resolve-config)
                (config/validate-config))]
    (start cfg)
    ;; the background embeddings sync must not keep the JVM alive
    (shutdown-agents)))