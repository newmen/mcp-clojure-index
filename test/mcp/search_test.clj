(ns mcp.search-test
  (:require [clojure.test :refer :all]
            [mcp.search :as sut]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.config :as config]
            [mcp.parser :as parser])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-search"
         :search/top-k 100
         :search/re-rank-top 10))

(defn- make-chunk
  [overrides]
  (merge
    {:chunk/id        (UUID/randomUUID)
     :chunk/ns        "test.ns"
     :chunk/file      "/fake/path.clj"
     :chunk/type      :fn
     :chunk/name      "my-func"
     :chunk/source    "(defn my-func [x] (* x 2))"
     :chunk/start-line 1
     :chunk/end-line   1
     :chunk/visibility :public
     :chunk/language  "clojure"
     :chunk/metadata  {}
     :chunk/symbols   #{}
     :chunk/hash      "abc123"}
    overrides))

(defn- make-sym
  [sym-name file-path chunk-id]
  {:sym/name       (symbol (str "test.ns/" (name sym-name)))
   :sym/simple     sym-name
   :sym/ns         'test.ns
   :sym/type       :fn
   :sym/file       file-path
   :sym/line       1
   :sym/arglists   nil
   :sym/doc        nil
   :sym/protocol   nil
   :sym/record     nil
   :sym/tag        nil
   :sym/chunk-id   chunk-id
   :sym/visibility :public
   :sym/aliases    #{}})

(deftest search-simple-empty-query
  (is (nil? (sut/search-simple test-config ""))))

(deftest search-simple-nil-query
  (is (nil? (sut/search-simple test-config nil))))

(deftest search-simple-throws-on-qdrant-error
  (is (thrown? Exception (sut/search-simple test-config "find user creation"))))

(deftest search-empty-query-returns-nil
  (is (nil? (sut/search test-config ""))))

(deftest search-nil-query-returns-nil
  (is (nil? (sut/search test-config nil))))

(deftest search-expands-with-symbols
  (let [cid-1 (UUID/randomUUID)
        cid-2 (UUID/randomUUID)
        cid-3 (UUID/randomUUID)
        cid-extra (UUID/randomUUID)
        file-path "/project/core.clj"
        sym-1 (make-sym 'process file-path cid-1)
        sym-2 (make-sym 'validate file-path cid-2)
        sym-3 (make-sym 'enrich file-path cid-3)
        sym-extra (make-sym 'format-name file-path cid-extra)
        index (-> (si/empty-index) (si/add-file! [sym-1 sym-2 sym-3 sym-extra]))
        chunk-map {cid-1 (make-chunk {:chunk/id cid-1 :chunk/name "process" :chunk/file file-path})
                   cid-2 (make-chunk {:chunk/id cid-2 :chunk/name "validate" :chunk/file file-path})
                   cid-3 (make-chunk {:chunk/id cid-3 :chunk/name "enrich" :chunk/file file-path})
                   cid-extra (make-chunk {:chunk/id cid-extra :chunk/name "format-name" :chunk/file file-path})}
        extra (#'sut/expand-with-symbols #{cid-1 cid-2} index chunk-map)
        extra-ids (into #{} (map :chunk/id) extra)]
    (is (contains? extra-ids cid-3))
    (is (contains? extra-ids cid-extra))))

(deftest search-expands-with-graph
  (let [cid-core (UUID/randomUUID)
        cid-utils (UUID/randomUUID)
        edge {:edge/from 'test.ns/process :edge/from-id cid-core
              :edge/to 'test.ns/enrich :edge/to-id cid-utils
              :edge/type :call :edge/file "/p.clj" :edge/line 1}
        gr (-> (graph/empty-graph) (graph/add-file! [edge]))
        extra-ids (#'sut/expand-with-graph #{cid-core} gr)]
    (is (contains? extra-ids cid-utils))))

(deftest search-graph-expansion-no-duplicates
  (let [cid (UUID/randomUUID)
        edge {:edge/from 'test.ns/foo :edge/from-id cid
              :edge/to 'test.ns/bar :edge/to-id cid
              :edge/type :call :edge/file "/p.clj" :edge/line 1}
        gr (-> (graph/empty-graph) (graph/add-file! [edge]))
        extra-ids (#'sut/expand-with-graph #{cid} gr)]
    (is (empty? extra-ids)
        "Self-loops should not produce extra ids")))

(deftest expand-with-symbols-empty-ids
  (let [index (si/empty-index)
        chunk-map {}
        extra (#'sut/expand-with-symbols #{} index chunk-map)]
    (is (empty? extra))))

(deftest expand-with-symbols-unknown-ids
  (let [index (si/empty-index)
        chunk-map {}
        extra (#'sut/expand-with-symbols #{UUID/randomUUID} index chunk-map)]
    (is (empty? extra))))

(deftest expand-with-graph-empty-ids
  (let [gr (graph/empty-graph)
        extra (#'sut/expand-with-graph #{} gr)]
    (is (empty? extra))))

(deftest expand-with-graph-unknown-ids
  (let [gr (graph/empty-graph)
        extra (#'sut/expand-with-graph #{UUID/randomUUID} gr)]
    (is (empty? extra))))

(deftest enrich-result-chunks-builds-chunk-map
  (let [cid (UUID/randomUUID)
        sym (make-sym 'my-func "/f.clj" cid)
        index (si/add-file! (si/empty-index) [sym])
        chunk-map (#'sut/enrich-result-chunks index)]
    (is (contains? chunk-map cid))
    (is (= (:chunk/id (get chunk-map cid)) cid))
    (is (= "my-func" (:chunk/name (get chunk-map cid))))))

(deftest enrich-result-chunks-empty-index
  (let [index (si/empty-index)
        chunk-map (#'sut/enrich-result-chunks index)]
    (is (empty? chunk-map))))

(deftest search-full-returns-nil-on-empty-query
  (let [index (si/empty-index)
        gr (graph/empty-graph)]
    (is (nil? (sut/search-full test-config "" index gr)))))

(deftest search-simple-returns-vector
  (let [cg1 (make-chunk {:chunk/name "create-user"})
        cg2 (make-chunk {:chunk/name "find-user"})]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  [{:chunk cg1 :score 0.85}
                                   {:chunk cg2 :score 0.72}])
                  embeddings/rerank (fn [_query candidates _cfg]
                                      (mapv (fn [c i]
                                              (assoc c :re-rank (float (- 1.0 (* i 0.1)))))
                                            (sort-by :score > candidates)
                                            (range)))]
      (let [results (sut/search-simple test-config "find user")]
        (is (vector? results))
        (is (pos? (count results)))
        (doseq [r results]
          (is (contains? r :chunk))
          (is (contains? r :score))
          (is (contains? r :re-rank)))))))

(deftest search-full-returns-expanded-results
  (let [cid-1 (UUID/randomUUID)
        cid-2 (UUID/randomUUID)
cg1 (make-chunk {:chunk/id cid-1 :chunk/name "create-user"
                           :chunk/file "/project/core.clj"})
        _cg2 (make-chunk {:chunk/id cid-2 :chunk/name "format-name"
                           :chunk/file "/project/core.clj"})
        sym-1 (make-sym 'create-user "/project/core.clj" cid-1)
        sym-2 (make-sym 'format-name "/project/core.clj" cid-2)
        index (si/add-file! (si/empty-index) [sym-1 sym-2])
        edge {:edge/from 'test.ns/create-user :edge/from-id cid-1
              :edge/to 'test.ns/format-name :edge/to-id cid-2
              :edge/type :call :edge/file "/project/core.clj" :edge/line 1}
        gr (graph/add-file! (graph/empty-graph) [edge])]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  [{:chunk cg1 :score 0.85}])
                  embeddings/rerank (fn [_query candidates _cfg]
                                      (mapv #(assoc % :re-rank (:score %)) candidates))]
      (let [results (sut/search-full test-config "find user" index gr)]
        (is (vector? results))
        (is (pos? (count results)))
        (is (every? #(contains? % :result/chunk) results))
        (is (every? #(contains? % :result/score) results))
        (is (every? #(contains? % :result/rank) results))
        (is (every? #(contains? % :result/re-rank) results))))))

(deftest search-full-results-are-ranked
  (let [cid-1 (UUID/randomUUID)
        cid-2 (UUID/randomUUID)
        cg1 (make-chunk {:chunk/id cid-1 :chunk/name "create-user"
                          :chunk/file "/project/core.clj"})
        cg2 (make-chunk {:chunk/id cid-2 :chunk/name "format-name"
                          :chunk/file "/project/core.clj"})
        sym-1 (make-sym 'create-user "/project/core.clj" cid-1)
        index (si/add-file! (si/empty-index) [sym-1])
        gr (graph/empty-graph)]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k]
                                  [{:chunk cg1 :score 0.85}
                                   {:chunk cg2 :score 0.72}])
                  embeddings/rerank (fn [_query candidates _cfg]
                                      (mapv (fn [c i]
                                              (let [s (- 1.0 (* i 0.1))]
                                                (assoc c :re-rank (float s))))
                                            (sort-by :score > candidates)
                                            (range)))]
      (let [results (sut/search-full test-config "find user" index gr)]
        (is (pos? (count results)))
        (let [ranks (map :result/rank results)]
          (is (= (sort ranks) ranks)
              "Results should be sorted by rank"))))))

(deftest search-with-parsed-chunks-returns-expected-structure
  (let [fixtures-dir "test-resources/fixtures"
        result (parser/parse-file (str fixtures-dir "/valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        chunks (:result/chunks enriched)
        cg1 (first chunks)
        _ (is (some? cg1) "Should have at least one chunk")]
    (with-redefs [embeddings/generate (fn [texts _cfg]
                                        (mapv (fn [_] [0.1 0.2 0.3]) texts))
                  qdrant/search (fn [_cfg _emb _k]
                                  (mapv (fn [c] {:chunk c :score 0.8}) (take 3 chunks)))
                  embeddings/rerank (fn [_query candidates _cfg]
                                      (mapv (fn [c i]
                                              (let [s (- 1.0 (* i 0.1))]
                                                (assoc c :re-rank (float s))))
                                            candidates
                                            (range)))]
      (let [results (sut/search test-config "find user" (si/empty-index) (graph/empty-graph) {})]
        (is (vector? results))
        (is (pos? (count results)))
        (doseq [r results]
          (is (contains? r :result/chunk))
          (is (contains? r :result/score))
          (is (contains? r :result/rank))
          (is (contains? r :result/re-rank)))))))

(deftest search-no-duplicate-expansion
  (let [cid-1 (UUID/randomUUID)
        cid-2 (UUID/randomUUID)
        cg1 (make-chunk {:chunk/id cid-1 :chunk/name "process"
                          :chunk/file "/project/core.clj"})
        cg2 (make-chunk {:chunk/id cid-2 :chunk/name "enrich"
                          :chunk/file "/project/core.clj"})
        sym-1 (make-sym 'process "/project/core.clj" cid-1)
        sym-2 (make-sym 'enrich "/project/core.clj" cid-2)
        index (si/add-file! (si/empty-index) [sym-1 sym-2])
        edge {:edge/from 'test.ns/process :edge/from-id cid-1
              :edge/to 'test.ns/enrich :edge/to-id cid-2
              :edge/type :call :edge/file "/project/core.clj" :edge/line 1}
        gr (graph/add-file! (graph/empty-graph) [edge])
        chunk-map {cid-1 cg1 cid-2 cg2}
        extra (#'sut/collect-expanded #{cid-1} index gr chunk-map)
        extra-ids (map :chunk/id extra)]
    (is (= 1 (count extra-ids)))
    (is (= cid-2 (first extra-ids)))))