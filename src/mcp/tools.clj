(ns mcp.tools
  (:require [clojure.string :as s]
            [mcp.search :as search]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph])
  (:import))

(set! *warn-on-reflection* true)

(defn- find-all-macros
  [index]
  (si/find-by-type index :macro))

(defn- symbol->result
  [sym]
  {:name      (str (:sym/name sym))
   :ns        (str (:sym/ns sym))
   :type      (name (:sym/type sym))
   :file      (:sym/file sym)
   :line      (:sym/line sym)
   :arglists  (when (:sym/arglists sym) (pr-str (:sym/arglists sym)))
   :doc       (:sym/doc sym)
   :visibility (name (:sym/visibility sym))})

(defn- search->result
  [r]
  (let [chunk (:result/chunk r)]
    {:id         (str (:chunk/id chunk))
     :file       (:chunk/file chunk)
     :symbol     (:chunk/name chunk)
     :ns         (:chunk/ns chunk)
     :type       (name (:chunk/type chunk))
     :source     (:chunk/source chunk)
     :start-line (:chunk/start-line chunk)
     :end-line   (:chunk/end-line chunk)
     :score      (:result/score r)
     :re-rank    (:result/re-rank r)}))

(def tool-definitions
  [{:name "semantic_search"
    :description "Search code semantically using vector search and reranking"
    :inputSchema {:type "object"
                  :properties {:query {:type "string" :description "Search query in natural language"}}
                  :required ["query"]}}
   {:name "find_symbol"
    :description "Find a symbol (function, macro, var) by name"
    :inputSchema {:type "object"
                  :properties {:name {:type "string" :description "Symbol name (simple or qualified)"}}
                  :required ["name"]}}
   {:name "find_namespace"
    :description "Find a namespace definition"
    :inputSchema {:type "object"
                  :properties {:name {:type "string" :description "Namespace name"}}
                  :required ["name"]}}
   {:name "find_callers"
    :description "Find all symbols that call a given symbol"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Qualified symbol name (e.g. my.ns/my-func)"}}
                  :required ["symbol"]}}
   {:name "find_callees"
    :description "Find all symbols called by a given symbol"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Qualified symbol name (e.g. my.ns/my-func)"}}
                  :required ["symbol"]}}
   {:name "find_protocol"
    :description "Find all methods of a protocol"
    :inputSchema {:type "object"
                  :properties {:name {:type "string" :description "Protocol name"}}
                  :required ["name"]}}
   {:name "find_record"
    :description "Find all methods of a record"
    :inputSchema {:type "object"
                  :properties {:name {:type "string" :description "Record name"}}
                  :required ["name"]}}
   {:name "find_macro"
    :description "List all macros in the index"
    :inputSchema {:type "object"
                  :properties {:name {:type "string" :description "Macro name (optional, returns all if empty)"}}
                  :required []}}])

(defn- map->text
  [m]
  (s/join "\n" (map (fn [[k v]] (str (name k) ": " v)) m)))

(defmulti handle-tool
  (fn [tool-name _args _state]
    (keyword tool-name)))

(defmethod handle-tool :semantic_search
  [_ params {:keys [config index graph-state]}]
  (let [query (or (:query params) "")]
    (if (s/blank? query)
      {:content [{:type "text" :text "Query is empty"}]}
      (let [results (search/search-full config query index graph-state)
            output (if (empty? results)
                     "No results found."
                     (s/join "\n---\n" (mapv (fn [r i]
                                               (str "Result #" (inc i) " (rank: " (:result/rank r) "):\n"
                                                    (map->text (search->result r))))
                                             results (range))))]
        {:content [{:type "text" :text output}]}))))

(defmethod handle-tool :find_symbol
  [_ params {:keys [index]}]
  (let [sym-name (or (:name params) "")
        results (si/find-symbol index sym-name)]
    (if (empty? results)
      {:content [{:type "text" :text (str "Symbol '" sym-name "' not found.")}]}
      {:content [{:type "text" :text (s/join "\n---\n" (mapv (comp map->text symbol->result) results))}]})))

(defmethod handle-tool :find_namespace
  [_ params {:keys [index]}]
  (let [ns-name (or (:name params) "")
        result (si/find-namespace index ns-name)]
    (if (nil? result)
      {:content [{:type "text" :text (str "Namespace '" ns-name "' not found.")}]}
      {:content [{:type "text" :text (pr-str result)}]})))

(defmethod handle-tool :find_callers
  [_ params {:keys [graph-state]}]
  (let [sym-str (or (:symbol params) "")
        sym (symbol sym-str)
        callers (graph/find-callers graph-state sym)]
    (if (empty? callers)
      {:content [{:type "text" :text (str "No callers found for '" sym-str "'.")}]}
      {:content [{:type "text" :text (s/join "\n" callers)}]})))

(defmethod handle-tool :find_callees
  [_ params {:keys [graph-state]}]
  (let [sym-str (or (:symbol params) "")
        sym (symbol sym-str)
        callees (graph/find-callees graph-state sym)]
    (if (empty? callees)
      {:content [{:type "text" :text (str "No callees found for '" sym-str "'.")}]}
      {:content [{:type "text" :text (s/join "\n" callees)}]})))

(defmethod handle-tool :find_protocol
  [_ params {:keys [index]}]
  (let [protocol-name (or (:name params) "")
        results (si/find-by-protocol index protocol-name)]
    (if (empty? results)
      {:content [{:type "text" :text (str "Protocol '" protocol-name "' not found.")}]}
      {:content [{:type "text" :text (s/join "\n---\n" (mapv (comp map->text symbol->result) results))}]})))

(defmethod handle-tool :find_record
  [_ params {:keys [index]}]
  (let [record-name (or (:name params) "")
        results (si/find-by-record index record-name)]
    (if (empty? results)
      {:content [{:type "text" :text (str "Record '" record-name "' not found.")}]}
      {:content [{:type "text" :text (s/join "\n---\n" (mapv (comp map->text symbol->result) results))}]})))

(defmethod handle-tool :find_macro
  [_ params {:keys [index]}]
  (let [macro-name (or (:name params) "")
        all-macros (find-all-macros index)
        results (if (and macro-name (not (s/blank? macro-name)))
                  (filter #(= (str (:sym/simple %)) macro-name) all-macros)
                  all-macros)]
    (if (empty? results)
      {:content [{:type "text" :text (if (and macro-name (not (s/blank? macro-name)))
                                        (str "Macro '" macro-name "' not found.")
                                        "No macros found in index.")}]}
      {:content [{:type "text" :text (s/join "\n---\n" (mapv (comp map->text symbol->result) results))}]})))

(defn make-initial-state
  [config]
  {:config config
   :index (si/empty-index)
   :graph-state (graph/empty-graph)
   :index-ref (atom (si/empty-index))
   :graph-ref (atom (graph/empty-graph))})

(defn handle-tools-list
  [_state]
  {:tools (vec (mapv (fn [t] (select-keys t [:name :description :inputSchema])) tool-definitions))})