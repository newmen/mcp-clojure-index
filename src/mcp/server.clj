(ns mcp.server
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.tools :as tools]
            [mcp.watcher :as watcher])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)))

(set! *warn-on-reflection* true)

(def ^:private protocol-version
  "First MCP revision whose `initialize` result carries `instructions`
   is 2025-03-26; this is the latest one the server follows."
  "2025-06-18")

(def ^:private instructions
  (str "Use this server instead of grep and reading files for questions about this Clojure code base.\n"
       "It resolves names like the compiler: aliases, :refer, #'var, with-redefs, functions passed as values, keywords in EDN.\n"
       "Answers are compact `path:line` lines with a hard size limit; page with `offset` when an answer says it is truncated.\n"
       "Start with find_definition for where a name is defined.\n"
       "Use get_source for a definition's body instead of reading the whole file."))
(defonce ^:private server-state-atom (atom nil))

(defn- json-rpc-error
  [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code :message message}})

(defn- json-rpc-result
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn- parse-json
  [^String json-str]
  (try (json/read-str json-str :key-fn keyword)
       (catch Exception _ nil)))

(defn- write-json
  [^OutputStreamWriter writer data]
  (let [s (json/write-str data)]
    (.write writer s)
    (.write writer "\n")
    (.flush writer)))

(defn- initialize-handler
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
  [state]
  (let [idx-ref (:index-ref state)
        code-index-ref (:code-index-ref state)]
    (cond-> state
      code-index-ref (assoc :code-index @code-index-ref)
      idx-ref (assoc :index @idx-ref))))

(defn- ensure-fresh-code-index!
  "Re-analyse files changed on disk since the last answer, synchronously,
   so a precise tool never answers from a stale index (ADR-0005)."
  [state]
  (when-let [code-index-ref (:code-index-ref state)]
    (try
      (swap! code-index-ref (partial code-index/refresh (:config state)))
      (catch Exception e
        (log/error e "Freshness check failed, answering from the last index"))))
  state)

(defn- handle-method
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
        (let [result (tools/handle-tools-list fresh)]
          [(json-rpc-result id result) state])
        "tools/call"
        (if-let [tool-name (get params :name)]
          (let [arguments (get params :arguments {})
                tool-result (tools/handle-tool tool-name arguments fresh)]
            [(json-rpc-result id {:content (:content tool-result)}) state])
          [(json-rpc-error id -32602 "Missing tool name") state])
        [(json-rpc-error id -32601 (str "Method not found: " method)) state])
      (catch Exception e
        [(json-rpc-error id -32603 (.getMessage e)) state]))))

(defn stop
  []
  (when-let [st @server-state-atom]
    (watcher/stop-watching! (:watcher st))
    (reset! server-state-atom nil)
    (log/info "Server stopped")))

(defn- process-message
  [^String line state]
  (let [msg (parse-json line)]
    (if (nil? msg)
      [nil state]
      (handle-method msg state))))

(defn start
  [cfg]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (OutputStreamWriter. System/out)
        _ (log/info "Starting MCP server with" (:server/transport cfg) "transport")
        initial (assoc (tools/make-initial-state cfg)
                       :code-index-ref (atom (code-index/build cfg)))
        _ (log/info "Code index built:" (count (code-index/files @(:code-index-ref initial))) "files")
        index-ref (:index-ref initial)
        graph-ref (:graph-ref initial)
        watcher-map (watcher/start-watching! cfg index-ref graph-ref)
        server-state (atom (assoc initial
                                  :watcher watcher-map))]
    (reset! server-state-atom @server-state)
    (log/info "MCP server ready on stdio (indexing in background)")
    (future
      (try
        (let [restore-result (watcher/restore-state! cfg)]
          (reset! index-ref (:index restore-result))
          (reset! graph-ref (:graph restore-result))
          (swap! server-state assoc :index @index-ref)
          (log/info "Background indexing complete"))
        (catch Exception e
          (log/error e "Background indexing failed:" (.getMessage e)))))
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
  [& args]
  (let [config-path (or (first args) "resources/config.edn")
        cfg (-> (config/load-config config-path)
                (config/resolve-config)
                (config/validate-config))]
    (start cfg)))