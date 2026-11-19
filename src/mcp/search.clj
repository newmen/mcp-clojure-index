(ns mcp.search
  "Semantic search over Search units: Qdrant vector search, then the reranker."
  (:require [mcp.qdrant :as qdrant]
            [mcp.embeddings :as embeddings]))

(set! *warn-on-reflection* true)

(defn- default-top-k
  [cfg]
  (or (:search/top-k cfg) 100))

(defn- default-rerank-top
  [cfg]
  (or (:search/re-rank-top cfg) 10))

(defn- qdrant-candidates
  [cfg embedding top-k]
  (mapv (fn [r]
          {:text (get-in r [:unit :text] "")
           :score (:score r 0.0)
           :unit (:unit r)})
        (qdrant/search cfg embedding top-k)))

(defn search
  "Semantic search: vector search in Qdrant, then reranking. No expansion.

   Parameters (in config):
     :search/top-k       - number of candidates from Qdrant (default 100)
     :search/re-rank-top - number of hits kept after reranking (default 10)

   Returns a vector of {:unit SearchUnit :score float :re-rank float},
   best first, or nil for a blank query."
  [cfg query]
  (let [top-k (default-top-k cfg)
        rerank-top (default-rerank-top cfg)]
    (when (and (seq query) (pos? top-k) (pos? rerank-top))
      (when-let [embedding (first (embeddings/generate [query] cfg))]
        (let [candidates (qdrant-candidates cfg embedding top-k)]
          (->> (when (seq candidates)
                 (embeddings/rerank query candidates cfg))
               (take rerank-top)
               (mapv #(select-keys % [:unit :score :re-rank]))))))))
