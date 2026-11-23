(ns mcp.tools.semantic-search
  "`semantic_search`: Search units by meaning, as Compact answer lines
   (ADR-0006). Needs Qdrant and Ollama; without them it says so and the
   precise tools keep working (ADR-0003)."
  (:require [clojure.string :as st]
            [clojure.tools.logging :as log]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.docstring :as docstring]
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
       "Answer: one line per hit (relevance score  path:line  kind  ns/name  Docstring summary), "
       "no source bodies, at most :output/limit lines and :output/max-chars characters; "
       "starts with `no confident matches` when even the best hit scores low; "
       "ends with `embeddings stale for N files` while recent edits are being embedded. "
       "Definitions in Test paths are left out unless `include_tests` is true. "
       "Use find_definition for known names and get_source for bodies."))

(defmethod tool/spec :semantic_search
  [_tool-key]
  {:name "semantic_search"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:query {:type "string"
                                             :description "What the code does, in natural language"}
                                     :include_tests {:type "boolean"
                                                     :description "Also search Definitions in Test paths (default false)"}}
                                    tool/paging-properties)
                 :required ["query"]}})

(def ^:private precise-tools-note
  "the precise tools (find_definition, get_source, ...) still work")

(def ^:private unavailable-answer
  (str "semantic search unavailable (Qdrant or Ollama not reachable); " precise-tools-note))

(def ^:private lost-collection-answer
  (str "semantic search unavailable: the Qdrant collection was deleted, emptied or replaced, "
       "every file will be embedded again; " precise-tools-note))

;; ---------------------------------------------------------------------------
;; Answer
;; ---------------------------------------------------------------------------

(defn- hit->line
  "The Compact answer line of a Hit:
   `score  path:line  kind  ns/name  Docstring summary`.

  The score is the reranker's relevance score (ADR-0006); without the
  reranker there is none, never a raw vector score. The line shows no
  Type signature, but `signature-strategy` keeps it out of the summary.

  AnswerContext -> Hit -> String"
  [{:keys [index signature-strategy]} {:keys [unit re-rank]}]
  (->> [(some->> re-rank double (format "%.2f"))
        (str (output/relative-path (code-index/root index) (:filename unit)) ":" (:row unit))
        (:kind unit)
        (:name unit)
        (:summary (docstring/definition-doc signature-strategy unit))]
       (remove st/blank?)
       (st/join "  ")))

(defn- hit-count
  "How many hits to ask the search for: `:search/re-rank-top`, or more
   when the requested page (the hits before `offset` and a page of `limit`
   within the Output budget) reaches beyond them.

  Config -> ToolArgs -> Long"
  [config params]
  (let [re-rank-top (config/setting config :search/re-rank-top)
        page-size (min (config/setting config :output/limit) (or (:limit params) re-rank-top))]
    (max re-rank-top
         (+ (max 0 (or (:offset params) 0)) page-size))))

(defn- test-files
  "The absolute paths of the files under the Test paths of `index`.

  CodeIndex -> #{String}"
  [index]
  (into #{} (filter (partial code-index/test-file? index)) (code-index/files index)))

(defn- stale-note
  "The footer saying how many files have stale embeddings; nil when none.

  SemanticIndex -> CodeIndex -> IO (Maybe String)"
  [semantic-index code-index]
  (let [stale-count (count (semantic-index/stale-files semantic-index code-index))]
    (when (pos? stale-count)
      (str "embeddings stale for " stale-count " files"))))

(defn- with-note
  "`text` followed by the line `note`, when there is one.

  String -> Maybe String -> String"
  [text note]
  (cond-> text note (str "\n" note)))

(defn- confidence-note
  "The first line saying that no hit is confident: the best relevance of
   `hits` is below `:search/min-relevance`. Nil when it is not, or when
   the reranker gave no scores.

  Config -> [Hit] -> Maybe String"
  [config hits]
  (when-let [best (some->> (seq (keep :re-rank hits)) (apply max) double)]
    (when (< best (config/setting config :search/min-relevance))
      (format "no confident matches (best %.2f), try rephrasing" best))))

;; Besides answering, `semantic_search` checks the collection of the
;; SemanticIndex before searching it and, when it was deleted, emptied or
;; replaced, makes the SemanticIndex forget it, so the next sync rebuilds it.
(defmethod tool/call :semantic_search
  [_tool-key params {:keys [config code-index semantic-index]}]
  (let [query (st/trim (str (:query params)))
        include-tests? (tool/flag params :include_tests false)]
    (tool/text-result
     (if (st/blank? query)
       "query is empty"
       (try
         (some-> semantic-index semantic-index/verify-collection!)
         (let [hits (search/search config query
                                   (search-units/search-units code-index)
                                   (if include-tests? #{} (test-files code-index))
                                   (hit-count config params))
               ctx (output/answer-context config params code-index)
               note (some-> semantic-index (stale-note code-index))
               lines (mapv (partial hit->line ctx) hits)]
           (if (empty? lines)
             (with-note (str "no results for: " query " " (output/checked code-index include-tests?)) note)
             (output/render-framed (:budget ctx)
                                   {:header (confidence-note config hits) :footer note}
                                   lines)))
         (catch Exception e
           (log/warn "semantic_search unavailable:" (.getMessage e))
           (if (some-> semantic-index (semantic-index/forget-if-collection-lost! e))
             (with-note lost-collection-answer (stale-note semantic-index code-index))
             unavailable-answer)))))))
