(ns mcp.search
  "Semantic search over Search units: Qdrant vector search and lexical
   matching on names and docstrings (`mcp.lexical`), merged by reciprocal
   rank fusion, then the reranker."
  (:require [mcp.embeddings :as embeddings]
            [mcp.lexical :as lexical]
            [mcp.qdrant :as qdrant]))

(set! *warn-on-reflection* true)

(def ^:private fusion-offset
  "Reciprocal rank fusion constant k: a candidate at rank r (from 1) of a
   ranking adds 1/(k + r) to its fused score. 60 is the usual choice; it
   keeps one ranking's first places from outweighing agreement of both."
  60)

;; Candidate shape (what the reranker receives):
;;
;; {:unit {:id "..." :filename ... :name ... :text ...} ; Search unit, `:id` as a string
;;  :text "..."                                     ; the unit's embedding text
;;  :score 0.87}                                    ; vector similarity, only from Qdrant

(defn- candidate
  [unit]
  {:unit unit
   :text (:text unit "")})

(defn- qdrant-candidates
  [cfg embedding top-k]
  (mapv (fn [{:keys [unit score]}] (assoc (candidate unit) :score score))
        (qdrant/search cfg embedding top-k)))

(defn- lexical-candidates
  "The `top-k` best lexical matches among `units`, shaped like the units
   Qdrant returns."
  [query units top-k]
  (->> (lexical/rank query units)
       (take top-k)
       (mapv (fn [unit]
               (candidate (-> unit
                              (select-keys [:id :filename :row :kind :name :doc :text])
                              (update :id str)))))))

(defn- fuse
  "Reciprocal rank fusion of `rankings` (vectors of candidates, best
   first): every candidate once, by the sum of 1/(k + rank) over the
   rankings it is in. Ties keep the order of first appearance; a candidate
   in several rankings is taken from the first of them."
  [rankings]
  (let [ids (distinct (mapcat (partial map (comp :id :unit)) rankings))
        by-id (into {} (map (juxt (comp :id :unit) identity)) (apply concat (reverse rankings)))
        fused-score (apply merge-with +
                           (for [ranking rankings
                                 [index {:keys [unit]}] (map-indexed vector ranking)]
                             {(:id unit) (/ 1.0 (+ fusion-offset index 1))}))]
    (mapv by-id (sort-by (comp - fused-score) ids))))

(defn search
  "Semantic search for `query`: vector search in Qdrant and lexical
   matching against `units` (all Search units of the project), fused,
   then reranked; at most `hit-count` hits. No expansion.

   `:search/top-k` in `cfg` is the number of candidates from each of
   Qdrant and the lexical matching, and of fused candidates reranked.

   Returns a vector of {:unit SearchUnit :re-rank float}, best first, or
   nil for a blank query. Without the reranker hits have no `:re-rank`
   and keep the fused order."
  [cfg query units hit-count]
  (let [top-k (:search/top-k cfg)]
    (when (and (seq query) (pos? top-k) (pos? hit-count))
      (when-let [embedding (first (embeddings/generate [query] cfg))]
        (let [candidates (->> (fuse [(qdrant-candidates cfg embedding top-k)
                                     (lexical-candidates query units top-k)])
                              (take top-k)
                              vec)]
          (->> (when (seq candidates)
                 (embeddings/rerank query candidates cfg))
               (take hit-count)
               (mapv #(select-keys % [:unit :re-rank]))))))))
