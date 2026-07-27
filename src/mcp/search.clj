(ns mcp.search
  (:require [clojure.string :as s]
            [clojure.set :as set]
            [mcp.qdrant :as qdrant]
            [mcp.embeddings :as embeddings]
            [mcp.graph :as graph]))

(set! *warn-on-reflection* true)

(defonce ^:private file-cache-atom (atom {}))
(defonce ^:private cache-max-size 50)

(defn- default-top-k
  [cfg]
  (or (:search/top-k cfg) 100))

(defn- default-rerank-top
  [cfg]
  (or (:search/re-rank-top cfg) 10))

(defn- qdrant-candidates
  [cfg embedding top-k]
  (mapv (fn [r]
          {:text (get-in r [:chunk :chunk/source] "")
           :score (:score r 0.0)
           :chunk (:chunk r)})
        (qdrant/search cfg embedding top-k)))

(defn- search-pipeline
  [cfg query]
  (let [top-k (default-top-k cfg)
        rerank-top (default-rerank-top cfg)]
    (when (and (seq query) (pos? top-k) (pos? rerank-top))
      (when-let [embedding (first (embeddings/generate [query] cfg))]
        (let [candidates (qdrant-candidates cfg embedding top-k)]
          (-> (if (seq candidates)
                (embeddings/rerank query candidates cfg)
                [])
              (->> (take rerank-top))))))))

(defn- expand-with-symbols
  "Given a set of chunk-ids and a symbol index, return additional context chunks.
   Looks up by simple name and by file."
  [chunk-ids index chunk-map]
  (let [chunks (keep chunk-map chunk-ids)
        files (into #{} (map :chunk/file) chunks)]
    (vec
      (for [f files
            sym (get-in index [:index/by-file f] [])
            :let [chunk (get chunk-map (:sym/chunk-id sym))]
            :when (and chunk (not (contains? chunk-ids (:chunk/id chunk))))]
        chunk))))

(defn- expand-with-graph
  "Given a set of chunk-ids and a graph, return callers and callees.
   Returns additional chunk-ids from callers/callees relationships."
  [chunk-ids gr]
  (let [edges (mapcat (fn [cid] (graph/find-edges-by-chunk gr cid)) chunk-ids)
        caller-ids (into #{} (keep :edge/from-id) edges)
        callee-ids (into #{} (keep :edge/to-id) edges)]
    (set/difference (set/union caller-ids callee-ids) chunk-ids)))

(defn- rank->search-result
  [r]
  {:result/chunk (:chunk r)
   :result/score (:score r)
   :result/rank (:rank r)
   :result/re-rank (:re-rank r)})

(defn- chunk->search-result
  [chunk next-rank]
  {:result/chunk chunk
   :result/score 0.0
   :result/rank next-rank
   :result/re-rank 0.0})

(defn- collect-expanded
  "Return extra chunks from symbol index and graph expansion, deduplicated."
  [chunk-ids index graph chunk-map]
  (let [sym-chunks (when (and index chunk-map)
                     (expand-with-symbols chunk-ids index chunk-map))
        graph-ids (when (and graph chunk-map)
                    (expand-with-graph chunk-ids graph))
        graph-chunks (when (seq graph-ids)
                       (vec (keep chunk-map graph-ids)))]
    (distinct (concat sym-chunks graph-chunks))))

(defn search
  "Hybrid search: semantic search via Qdrant → reranking via Cross Encoder → optional expansion via symbol index and graph.
   
   Parameters (in config):
     :search/top-k       - number of candidates from Qdrant (default 100)
     :search/re-rank-top - number of results after reranking (default 10)
   
   Returns a vector of SearchResult maps:
     {:result/chunk  CodeChunk
      :result/score  float    ; original cosine distance from Qdrant
      :result/rank   int      ; rank after reranking (1 = best)
      :result/re-rank float}  ; reranker score"
  ([cfg query]
   (search cfg query nil nil nil))
  ([cfg query index graph chunk-map]
   (when-let [reranked (search-pipeline cfg query)]
     (let [ranked (map-indexed (fn [i r] (assoc r :rank (inc i))) reranked)
           chunk-ids (into #{} (keep (comp :chunk/id :chunk)) ranked)
           extra (collect-expanded chunk-ids index graph chunk-map)
           result-count (count ranked)]
       (vec (concat (mapv rank->search-result ranked)
                    (mapv #(chunk->search-result % (inc result-count)) extra)))))))

(defn search-simple
  "Simplified hybrid search without expansion.
   Returns seq of {:chunk CodeChunk :score float :re-rank float}."
  [cfg query]
  (when-let [reranked (search-pipeline cfg query)]
    (mapv (fn [r]
            {:chunk (:chunk r)
             :score (:score r)
             :re-rank (:re-rank r)})
          reranked)))

(defn- read-chunk-source
  "Try to read source code for a chunk from the file system.
   Caches file contents to avoid repeated disk I/O within a search."
  [file-path start-line end-line]
  (try
    (let [lines (or (get @file-cache-atom file-path)
                    (let [lines (vec (s/split-lines (slurp file-path)))]
                      (swap! file-cache-atom
                             (fn [c]
                               (if (>= (count c) cache-max-size)
                                 (assoc (into {} (drop 1 c)) file-path lines)
                                 (assoc c file-path lines))))
                      lines))]
      (s/join "\n" (subvec lines (dec start-line) end-line)))
    (catch Exception _ nil)))

(defn- enrich-result-chunks
  "Build a chunk-map (chunk-id → CodeChunk) from symbol index records.
   Reads source code from disk for each chunk."
  [index]
  (let [all-syms (vals (:index/by-qname index))]
    (into {}
          (keep (fn [sym]
                  (when-let [cid (:sym/chunk-id sym)]
                    (let [file (:sym/file sym)
                          sline (:sym/line sym)
                          eline (or (:sym/end-line sym) sline)]
                      [cid {:chunk/id cid
                            :chunk/ns (str (:sym/ns sym))
                            :chunk/file file
                            :chunk/type (:sym/type sym)
                            :chunk/name (str (:sym/simple sym))
                            :chunk/start-line sline
                            :chunk/end-line eline
                            :chunk/visibility (:sym/visibility sym)
                            :chunk/source (read-chunk-source file sline eline)
                            :chunk/language "clojure"
                            :chunk/hash nil
                            :sym/name (:sym/name sym)}]))))
          all-syms)))

(defn search-full
  "Full hybrid search with symbol and graph expansion.
   Returns vector of SearchResult maps."
  [cfg query index graph]
  (let [chunk-map (enrich-result-chunks index)]
    (search cfg query index graph chunk-map)))