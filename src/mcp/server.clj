(ns mcp.server
  (:require [clojure.data.json :as json]
            [mcp.config :as config]
            [mcp.tools :as tools]
            [mcp.watcher :as watcher])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)))

(set! *warn-on-reflection* true)

(def ^:private protocol-version "2024-11-05")
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
  [s]
  (try (json/read-str s :key-fn keyword)
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
                          :serverInfo server-info})
     state]))

(defn- live-state
  [state]
  (let [idx-ref (:index-ref state)
        gr-ref (:graph-ref state)]
    (cond-> state
      idx-ref (assoc :index @idx-ref)
      gr-ref (assoc :graph-state @gr-ref))))

(defn- handle-method
  [msg state]
  (let [method (:method msg)
        id (:id msg)
        params (:params msg)
        fresh (live-state state)]
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
    (println "[server] Server stopped")))

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
        _ (println "[server] Starting MCP server with" (:server/transport cfg) "transport")
        initial (tools/make-initial-state cfg)
        index-ref (:index-ref initial)
        graph-ref (:graph-ref initial)
        restore-result (watcher/restore-state! cfg)
        _ (reset! index-ref (:index restore-result))
        _ (reset! graph-ref (:graph restore-result))
        watcher-map (watcher/start-watching! cfg index-ref graph-ref)
        server-state (atom (assoc initial
                                  :index @index-ref
                                  :graph-state @graph-ref
                                  :watcher watcher-map))]
    (reset! server-state-atom @server-state)
    (println "[server] MCP server ready on stdio")
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
        (println "[server] Error:" (.getMessage e))
        (println "[server] Stopping..."))
      (finally
        (stop)))))

(defn -main
  [& args]
  (let [config-path (or (first args) "resources/config.edn")
        cfg (-> (config/load-config config-path)
                (config/validate-config))]
    (start cfg)))