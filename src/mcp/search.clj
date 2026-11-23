(ns mcp.search
  "Semantic search over Search units: Qdrant vector search and lexical
   matching on names and docstrings (`mcp.lexical`), merged by reciprocal
   rank fusion, then the reranker."
  (:require [clojure.spec.alpha :as s]
            [mcp.config :as config]
            [mcp.embeddings :as embeddings]
            [mcp.lexical :as lexical]
            [mcp.qdrant :as qdrant]))

(set! *warn-on-reflection* true)

(def ^:private fusion-offset
  "Reciprocal rank fusion constant k: a candidate at rank r (from 1) of a
   ranking adds 1/(k + r) to its fused score. 60 is the usual choice; it
   keeps one ranking's first places from outweighing agreement of both.

  Long"
  60)

;; Candidates are what the reranker receives; their shape is in
;; `mcp.embeddings`. A Hit is what `search` returns: {:unit :re-rank}, without
;; `:re-rank` when the reranker is unavailable.

(s/def ::hit (s/keys :req-un [::unit] :opt-un [::re-rank]))

(defn- candidate
  "The Candidate of a Search unit, without a score.

  SearchUnit -> Candidate"
  [unit]
  {:unit unit
   :text (:text unit "")})

(defn- qdrant-candidates
  "The `top-k` Candidates nearest to `embedding` in Qdrant outside
   `excluded-files`, with their vector similarity.

  Config -> Embedding -> Long -> #{String} -> IO [Candidate]"
  [cfg embedding top-k excluded-files]
  (mapv (fn [{:keys [unit score]}] (assoc (candidate unit) :score score))
        (qdrant/search cfg embedding top-k excluded-files)))

(defn- lexical-candidates
  "The `top-k` best lexical matches among `units`, shaped like the units
   Qdrant returns.

  String -> [SearchUnit] -> Long -> [Candidate]"
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
   in several rankings is taken from the first of them.

  [[Candidate]] -> [Candidate]"
  [rankings]
  (let [ids (distinct (mapcat (partial map (comp :id :unit)) rankings))
        by-id (into {} (map (juxt (comp :id :unit) identity)) (apply concat (reverse rankings)))
        fused-score (apply merge-with +
                           (for [ranking rankings
                                 [index {:keys [unit]}] (map-indexed vector ranking)]
                             {(:id unit) (/ 1.0 (+ fusion-offset index 1))}))]
    (mapv by-id (sort-by (comp - fused-score) ids))))

(defn search
  "Runs semantic search for `query`: vector search in Qdrant and lexical
   matching against `units` (all Search units of the project), fused,
   then reranked; at most `hit-count` hits. No expansion.

   Search units of `excluded-files` (such as those in Test paths) are left
   out of both before ranking; Qdrant leaves them out itself, so they do
   not take the places of other candidates.

   `:search/top-k` in `cfg` is the number of candidates from each of
   Qdrant and the lexical matching, and of fused candidates reranked.

   Returns a vector of {:unit SearchUnit :re-rank float}, best first, or
   nil for a blank query. Without the reranker hits have no `:re-rank`
   and keep the fused order.

  Config -> String -> [SearchUnit] -> #{String} -> Long -> IO (Maybe [Hit])"
  [cfg query units excluded-files hit-count]
  (let [top-k (config/setting cfg :search/top-k)]
    (when (and (seq query) (pos? top-k) (pos? hit-count))
      (when-let [embedding (first (embeddings/generate [query] cfg))]
        (let [candidates (->> (fuse [(qdrant-candidates cfg embedding top-k excluded-files)
                                     (lexical-candidates query
                                                         (remove (comp (set excluded-files) :filename) units)
                                                         top-k)])
                              (take top-k)
                              vec)]
          (->> (when (seq candidates)
                 (embeddings/rerank query candidates cfg))
               (take hit-count)
               (mapv #(select-keys % [:unit :re-rank]))))))))
