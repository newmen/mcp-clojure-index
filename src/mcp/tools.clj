(ns mcp.tools
  "Serves `tools/list` and `tools/call` from the tool registry (`mcp.tool`).
   New tools live in their own `mcp.tools.*` namespace and are required here."
  (:require [clojure.string :as s]
            [mcp.output :as output]
            [mcp.search :as search]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.tool :as tool]
            [mcp.tools.find-definition]
            [mcp.tools.find-usages]))

(set! *warn-on-reflection* true)

(defn- find-all-macros
  [index]
  (si/find-by-type index :macro))

(defn- symbol->result
  [root-path sym]
  {:name      (str (:sym/name sym))
   :ns        (str (:sym/ns sym))
   :type      (name (:sym/type sym))
   :file      (output/relative-path root-path (:sym/file sym))
   :line      (:sym/line sym)
   :arglists  (when (:sym/arglists sym) (pr-str (:sym/arglists sym)))
   :doc       (:sym/doc sym)
   :visibility (name (:sym/visibility sym))})

(defn- hit-docstring
  [index chunk]
  (when (and (:chunk/ns chunk) (:chunk/name chunk))
    (get-in index [:index/by-qname (symbol (:chunk/ns chunk) (:chunk/name chunk)) :sym/doc])))

(defn- hit->line
  "Compact answer line for one semantic search hit:
   `score  path:line  kind  ns/name  first docstring line`."
  [root-path index {:keys [chunk re-rank score]}]
  (->> [(format "%.2f" (double (or re-rank score 0.0)))
        (str (output/relative-path root-path (:chunk/file chunk)) ":" (:chunk/start-line chunk))
        (some-> (:chunk/type chunk) name)
        (str (:chunk/ns chunk) "/" (:chunk/name chunk))
        (output/first-line (hit-docstring index chunk))]
       (remove s/blank?)
       (s/join "  ")))

(defn- semantic-search-config
  "Ask the search for enough hits to fill the requested page."
  [config params]
  (let [page-size (min (:output/limit config)
                       (or (:limit params) (:search/re-rank-top config)))]
    (update config :search/re-rank-top
            (fnil max 0) (+ (or (:offset params) 0) page-size))))

(def ^:private legacy-tool-specs
  [{:name "semantic_search"
    :description "Search code semantically using vector search and reranking. Answer: one line per hit (score  path:line  kind  ns/name  first docstring line), no source bodies; at most :output/limit lines and :output/max-chars characters."
    :inputSchema {:type "object"
                  :properties {:query {:type "string" :description "Search query in natural language"}
                               :limit {:type "integer" :description "Maximum number of hits (can only lower the server limit)"}
                               :offset {:type "integer" :description "Number of hits to skip, for paging"}}
                  :required ["query"]}}
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
  (->> (map (fn [[k v]] (str (name k) ": " v)) m)
       (s/join "\n")))

(defn- symbols->answer
  [config params syms]
  (->> syms
       (mapv (comp map->text (partial symbol->result (:index/root-path config))))
       (output/render config params)))

(doseq [{tool-name :name :as tool-spec} legacy-tool-specs]
  (defmethod tool/spec (keyword tool-name) [_tool-key] tool-spec))

(defmethod tool/call :semantic_search
  [_ params {:keys [config index]}]
  (let [query (or (:query params) "")]
    (if (s/blank? query)
      {:content [{:type "text" :text "Query is empty"}]}
      (let [hits (search/search (semantic-search-config config params) query)
            answer (if (empty? hits)
                     "No results found."
                     (output/render config params
                                    (mapv (partial hit->line (:index/root-path config) index) hits)))]
        {:content [{:type "text" :text answer}]}))))

(defmethod tool/call :find_protocol
  [_ params {:keys [config index]}]
  (let [protocol-name (or (:name params) "")
        results (si/find-by-protocol index protocol-name)]
    (if (empty? results)
      {:content [{:type "text" :text (str "Protocol '" protocol-name "' not found.")}]}
      {:content [{:type "text" :text (symbols->answer config params results)}]})))

(defmethod tool/call :find_record
  [_ params {:keys [config index]}]
  (let [record-name (or (:name params) "")
        results (si/find-by-record index record-name)]
    (if (empty? results)
      {:content [{:type "text" :text (str "Record '" record-name "' not found.")}]}
      {:content [{:type "text" :text (symbols->answer config params results)}]})))

(defmethod tool/call :find_macro
  [_ params {:keys [config index]}]
  (let [macro-name (or (:name params) "")
        all-macros (find-all-macros index)
        results (if (s/blank? macro-name)
                  all-macros
                  (filter #(= (str (:sym/simple %)) macro-name) all-macros))]
    (if (empty? results)
      {:content [{:type "text" :text (if (s/blank? macro-name)
                                       "No macros found in index."
                                       (str "Macro '" macro-name "' not found."))}]}
      {:content [{:type "text" :text (symbols->answer config params results)}]})))

(defn make-initial-state
  [config]
  {:config config
   :index (si/empty-index)
   :index-ref (atom (si/empty-index))
   :graph-ref (atom (graph/empty-graph))})

(defn tool-definitions
  "The `tools/list` entries of all registered tools, sorted by name."
  []
  (->> (keys (methods tool/spec))
       (remove #{:default})
       (map tool/spec)
       (sort-by :name)
       vec))

(defn handle-tools-list
  "Answer `tools/list`."
  [_state]
  {:tools (tool-definitions)})

(defn handle-tool
  "Answer `tools/call` of `tool-name` with `args` in `state`."
  [tool-name args state]
  (tool/call (keyword tool-name) args state))