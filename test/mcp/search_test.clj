(ns mcp.search-test
  (:require [clojure.test :refer [deftest is]]
            [mcp.search :as sut]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.config :as config])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-search"
         :search/top-k 100
         :search/re-rank-top 10))

(defn- make-unit
  [unit-name]
  {:id (str (UUID/randomUUID))
   :file "/fake/path.clj"
   :row 1
   :kind "defn"
   :name (str "test.ns/" unit-name)
   :text (str "test.ns/" unit-name "\n(defn " unit-name " [x] (* x 2))")})

(defn- rerank-by-position
  [_query candidates _cfg]
  (mapv (fn [c i] (assoc c :re-rank (float (- 1.0 (* i 0.1)))))
        (sort-by :score > candidates)
        (range)))

(deftest search-empty-query-returns-nil
  (is (nil? (sut/search test-config ""))))

(deftest search-nil-query-returns-nil
  (is (nil? (sut/search test-config nil))))

(deftest search-throws-on-qdrant-error
  (is (thrown? Exception (sut/search test-config "find user creation"))))

(deftest search-returns-only-reranked-hits-without-expansion
  (let [cg1 (make-unit "create-user")
        cg2 (make-unit "find-user")]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  [{:unit cg1 :score 0.85}
                                   {:unit cg2 :score 0.72}])
                  embeddings/rerank rerank-by-position]
      (is (= [{:unit cg1 :score 0.85 :re-rank (float 1.0)}
              {:unit cg2 :score 0.72 :re-rank (float 0.9)}]
             (sut/search test-config "find user"))))))

(deftest search-keeps-at-most-re-rank-top-hits
  (let [units (mapv #(make-unit (str "f" %)) (range 5))]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  (mapv (fn [c] {:unit c :score 0.5}) units))
                  embeddings/rerank rerank-by-position]
      (is (= 2 (count (sut/search (assoc test-config :search/re-rank-top 2) "f")))))))

;; ---------------------------------------------------------------------------
;; Edge cases: search with various configs
;; ---------------------------------------------------------------------------

(deftest search-with-zero-top-k
  (let [cfg (assoc test-config :search/top-k 0)]
    (is (nil? (sut/search cfg "test")))))

(deftest search-with-zero-rerank-top
  (let [cfg (assoc test-config :search/re-rank-top 0)]
    (is (nil? (sut/search cfg "test")))))

(deftest search-with-no-candidates-returns-empty
  (let [long-query (apply str (repeat 500 "search term "))]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k] [])
                  embeddings/rerank rerank-by-position]
      (is (= [] (sut/search test-config long-query))))))

;; ---------------------------------------------------------------------------
;; Edge cases: qdrant-candidates formatting
;; ---------------------------------------------------------------------------

(deftest qdrant-candidates-format-correctly
  (let [cg (assoc (make-unit "foo") :text "(defn foo [x] x)")]
    (with-redefs [qdrant/search (fn [_cfg _emb _k]
                                  [{:unit cg :score 0.95}])]
      (let [candidates (#'sut/qdrant-candidates test-config [0.1 0.2 0.3] 10)]
        (is (= 1 (count candidates)))
        (is (= "(defn foo [x] x)" (:text (first candidates))))
        (is (= 0.95 (:score (first candidates))))
        (is (= cg (:unit (first candidates))))))))
