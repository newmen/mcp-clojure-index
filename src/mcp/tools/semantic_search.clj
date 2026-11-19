(ns mcp.tools.semantic-search
  "`semantic_search`: Search units by meaning, as Compact answer lines
   (ADR-0006). Needs Qdrant and Ollama; without them it says so and the
   precise tools keep working (ADR-0003)."
  (:require [clojure.string :as s]
            [clojure.tools.logging :as log]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.search :as search]
            [mcp.search-units :as search-units]
            [mcp.semantic-index :as semantic-index]
            [mcp.tool :as tool]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find Clojure Definitions (functions, macros, tests, multimethods, specs, records, namespaces) "
       "and top-level EDN config entries by meaning, when you do not know their names. "
       "Unlike grep, matches intent rather than text (vector search and word matching on names "
       "and docstrings, then a reranker). "
       "Answer: one line per hit (relevance score  path:line  kind  ns/name  first docstring line), "
       "no source bodies, at most :output/limit lines and :output/max-chars characters; "
       "ends with `embeddings stale for N files` while recent edits are being embedded. "
       "Use find_definition for known names and get_source for bodies."))

(defmethod tool/spec :semantic_search
  [_tool-key]
  {:name "semantic_search"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:query {:type "string"
                                             :description "What the code does, in natural language"}}
                                    tool/paging-properties)
                 :required ["query"]}})

(def ^:private unavailable-answer
  (str "semantic search unavailable (Qdrant or Ollama not reachable); "
       "the precise tools (find_definition, get_source, ...) still work"))

;; ---------------------------------------------------------------------------
;; Answer
;; ---------------------------------------------------------------------------

(defn- hit->line
  "`score  path:line  kind  ns/name  first docstring line`. The score is
   the reranker's relevance score (ADR-0006); without the reranker there
   is none, never a raw vector score."
  [root {:keys [unit re-rank]}]
  (->> [(some->> re-rank double (format "%.2f"))
        (str (output/relative-path root (:filename unit)) ":" (:row unit))
        (:kind unit)
        (:name unit)
        (output/first-line (:doc unit))]
       (remove s/blank?)
       (s/join "  ")))

(defn- hit-count
  "How many hits to ask the search for: `:search/re-rank-top`, or more
   when the requested page (the hits before `offset` and a page of `limit`
   within the Output budget) reaches beyond them."
  [config params]
  (let [page-size (min (:output/limit config)
                       (or (:limit params) (:search/re-rank-top config)))]
    (max (:search/re-rank-top config)
         (+ (max 0 (or (:offset params) 0)) page-size))))

(defn- stale-note
  [semantic-state code-index]
  (let [stale-count (count (semantic-index/stale-files semantic-state code-index))]
    (when (pos? stale-count)
      (str "embeddings stale for " stale-count " files"))))

(defmethod tool/call :semantic_search
  [_tool-key params {:keys [config code-index semantic-index]}]
  (let [query (s/trim (str (:query params)))]
    (tool/text-result
     (if (s/blank? query)
       "query is empty"
       (try
         (let [hits (search/search config query
                                   (search-units/search-units code-index)
                                   (hit-count config params))
               note (some-> semantic-index (stale-note code-index))
               lines (mapv (partial hit->line (code-index/root code-index)) hits)]
           (if (empty? lines)
             (->> [(str "no results for: " query " " (output/checked code-index)) note]
                  (remove nil?)
                  (s/join "\n"))
             (output/render-framed config params {:footer note} lines)))
         (catch Exception e
           (log/warn "semantic_search unavailable:" (.getMessage e))
           unavailable-answer))))))
