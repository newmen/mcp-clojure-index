(ns mcp.search-test
  (:require [clojure.test :refer [deftest is]]
            [mcp.search :as sut]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.config :as config]))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-search"
         :search/top-k 100
         :search/re-rank-top 10))

(defn- make-unit
  ([unit-name] (make-unit unit-name nil))
  ([unit-name doc]
   (cond-> {:id (str "id-" unit-name)
            :file "/fake/path.clj"
            :row 1
            :kind "defn"
            :name (str "test.ns/" unit-name)
            :text (str "test.ns/" unit-name "\n(defn " unit-name " [x] (* x 2))")}
     doc (assoc :doc doc :docstring doc))))

(defn- reverse-rerank
  "A reranker that likes the candidates in reverse order: scores them and
   returns them best first."
  [_query candidates _cfg]
  (mapv (fn [candidate position] (assoc candidate :re-rank (float (- 1.0 (* position 0.1)))))
        (reverse candidates)
        (range)))

(defn- no-rerank
  [_query candidates _cfg]
  candidates)

(defn- search-names
  "Names of the hits for `query`, with Qdrant answering `vector-units` and
   the reranker `rerank`."
  [query vector-units all-units rerank]
  (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                qdrant/search (fn [_cfg _emb _k]
                                (mapv (fn [unit] {:unit unit :score 0.5}) vector-units))
                embeddings/rerank rerank]
    (mapv (comp :name :unit) (sut/search test-config query all-units))))

(deftest search-empty-query-returns-nil
  (is (nil? (sut/search test-config "" []))))

(deftest search-nil-query-returns-nil
  (is (nil? (sut/search test-config nil []))))

(deftest search-throws-on-qdrant-error
  (is (thrown? Exception (sut/search test-config "find user creation" []))))

(deftest search-keeps-at-most-re-rank-top-hits
  (let [units (mapv #(make-unit (str "f" %)) (range 5))]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  (mapv (fn [c] {:unit c :score 0.5}) units))
                  embeddings/rerank reverse-rerank]
      (is (= 2 (count (sut/search (assoc test-config :search/re-rank-top 2) "f" units)))))))

;; ---------------------------------------------------------------------------
;; Rank fusion of vector and lexical candidates
;; ---------------------------------------------------------------------------

(def ^:private create-user (make-unit "create-user" "Insert a new account."))
(def ^:private find-user (make-unit "find-user" "Look up an account by id."))
(def ^:private delete-user (make-unit "delete-user" "Remove an account."))
(def ^:private all-units [create-user find-user delete-user])

(deftest without-lexical-matches-the-vector-order-is-kept
  (is (= ["test.ns/create-user" "test.ns/find-user"]
         (search-names "xyzzy" [create-user find-user] all-units no-rerank))))

(deftest a-unit-matched-only-by-its-words-is-a-hit
  (is (= ["test.ns/create-user" "test.ns/delete-user"]
         (search-names "remove" [create-user] all-units no-rerank))))

(deftest a-unit-high-in-both-rankings-comes-first
  (is (= ["test.ns/find-user" "test.ns/create-user"]
         (search-names "look up" [create-user find-user] all-units no-rerank))))

(deftest a-unit-in-both-rankings-is-one-hit
  (is (= ["test.ns/delete-user"]
         (search-names "remove" [delete-user] all-units no-rerank))))

(deftest the-reranker-order-decides
  (is (= ["test.ns/find-user" "test.ns/create-user"]
         (search-names "xyzzy" [create-user find-user] all-units reverse-rerank))))

(deftest hits-carry-the-relevance-score-of-the-reranker
  (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                qdrant/search (fn [_cfg _emb _k] [{:unit create-user :score 0.85}])
                embeddings/rerank reverse-rerank]
    (is (= [{:unit create-user :re-rank (float 1.0)}]
           (sut/search test-config "xyzzy" all-units)))))

(deftest search-with-zero-top-k
  (let [cfg (assoc test-config :search/top-k 0)]
    (is (nil? (sut/search cfg "test" all-units)))))

(deftest search-with-zero-rerank-top
  (let [cfg (assoc test-config :search/re-rank-top 0)]
    (is (nil? (sut/search cfg "test" all-units)))))

(deftest search-with-no-candidates-returns-empty
  (is (= [] (search-names "xyzzy" [] all-units no-rerank))))
