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
            [mcp.tool :as tool])
  (:import (java.lang Character$UnicodeScript)))

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
       "Generated code can be left out for good with `:search/exclude` (path regexes) "
       "in the Project config `.clojure-index.edn` at the project root. "
       "Describe what the code does in the words its docstrings would use, "
       "not the identifiers you expect; a query in the docstrings' language matches best. "
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

(defn- left-out-files
  "The absolute paths of the files of `index` whose Search units are no
   hits: those missing from `searched-index` (under a Search exclusion)
   and, unless `include-tests?`, those under the Test paths. Qdrant leaves
   their points out itself, so points of a newly excluded file that a
   sync has not deleted yet never show.

  CodeIndex -> CodeIndex -> Boolean -> #{String}"
  [index searched-index include-tests?]
  (let [searched (set (code-index/files searched-index))]
    (cond-> (into #{} (remove searched) (code-index/files index))
      (not include-tests?) (into (test-files searched-index)))))

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

(defn- majority
  "The value that makes up more than half of `values`; nil when none does.

  [a] -> Maybe a"
  [values]
  (when-let [[value value-count] (some->> (seq (frequencies values)) (apply max-key val))]
    (when (> (* 2 value-count) (count values))
      value)))

(defn- main-script
  "The Unicode script of more than half of the letters of `text`; nil
   when no script holds that many.

  String -> Maybe Character$UnicodeScript"
  [^String text]
  (->> (.toArray (.codePoints text))
       (filter #(Character/isLetter (int %)))
       (map #(Character$UnicodeScript/of (int %)))
       majority))

(defn- script-name
  "The English name of `script`, such as `Cyrillic` or `Old Italic`.

  Character$UnicodeScript -> String"
  [^Character$UnicodeScript script]
  (->> (st/split (st/lower-case (.name script)) #"_")
       (map st/capitalize)
       (st/join " ")))

(defn- descriptions-script
  "The English name of the non-Latin script in which more than half of
   the Descriptions of `units` are mostly written; nil when there is none.

  [SearchUnit] -> Maybe String"
  [units]
  (let [script (->> (keep :doc units)
                    (map #(or (main-script %) :mixed))
                    majority)]
    (when (and (instance? Character$UnicodeScript script)
               (not= Character$UnicodeScript/LATIN script))
      (script-name script))))

(defn- confidence-note
  "The first line saying that no hit is confident: the best relevance of
   `hits` is below `:search/min-relevance`, with a hint to rephrase that
   names the script of the Descriptions of the searched `units` when it is
   not Latin. Nil when the best hit is confident, or when the reranker gave
   no scores.

  Config -> [SearchUnit] -> [Hit] -> Maybe String"
  [config units hits]
  (when-let [best (some->> (seq (keep :re-rank hits)) (apply max) double)]
    (when (< best (config/setting config :search/min-relevance))
      (str (format "no confident matches (best %.2f), try rephrasing in the docstrings' words" best)
           (some->> (descriptions-script units) (format " (they are mostly in %s)"))))))

;; Besides answering, `semantic_search` checks the collection of the
;; SemanticIndex before searching it and, when it was deleted, emptied or
;; replaced, makes the SemanticIndex forget it, so the next sync rebuilds it.
;; It searches, counts and checks for stale embeddings only the files outside
;; the Search exclusions; the precise tools keep seeing them.
(defmethod tool/call :semantic_search
  [_tool-key params {:keys [config semantic-index] full-index :code-index}]
  (let [query (st/trim (str (:query params)))
        include-tests? (tool/flag params :include_tests false)
        code-index (code-index/without-search-exclusions config full-index)]
    (tool/text-result
     (if (st/blank? query)
       "query is empty"
       (try
         (some-> semantic-index semantic-index/verify-collection!)
         (let [units (search-units/search-units code-index)
               left-out (left-out-files full-index code-index include-tests?)
               hits (search/search config query units left-out (hit-count config params))
               ctx (output/answer-context config params code-index)
               note (some-> semantic-index (stale-note code-index))
               lines (mapv (partial hit->line ctx) hits)]
           (if (empty? lines)
             (with-note (str "no results for: " query " " (output/checked code-index include-tests?)) note)
             (output/render-framed (:budget ctx)
                                   {:header (confidence-note config (remove (comp left-out :filename) units) hits)
                                    :footer note}
                                   lines)))
         (catch Exception e
           (log/warn "semantic_search unavailable:" (.getMessage e))
           (if (some-> semantic-index (semantic-index/forget-if-collection-lost! e))
             (with-note lost-collection-answer (stale-note semantic-index code-index))
             unavailable-answer)))))))
