(ns mcp.qdrant-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [mcp.qdrant :as sut]
            [mcp.config :as config]
            [mcp.parser :as parser])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 16333
         :qdrant/collection "mcp-test-collection"))

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
     :chunk/hash      "abc123def456"}
    overrides))

;; ---------------------------------------------------------------------------
;; Payload construction
;; ---------------------------------------------------------------------------

(deftest chunk->payload-contains-all-required-fields
  (let [chunk (make-chunk {:chunk/ns "bank.user"
                           :chunk/name "create-user"
                           :chunk/type :fn
                           :chunk/visibility :public
                           :chunk/file "src/bank/user.clj"
                           :chunk/start-line 81
                           :chunk/end-line 120
                           :chunk/source "(defn create-user [name] ...)"
                           :chunk/hash "6d0b9d7..."
                           :chunk/language "clojure"})
        p (#'sut/chunk->payload chunk)]
    (is (= "bank.user" (get p "namespace")))
    (is (= "create-user" (get p "symbol")))
    (is (= "function" (get p "type")))
    (is (= "public" (get p "visibility")))
    (is (= "src/bank/user.clj" (get p "file")))
    (is (= 81 (get p "start_line")))
    (is (= 120 (get p "end_line")))
    (is (= "6d0b9d7..." (get p "hash")))
    (is (= "(defn create-user [name] ...)" (get p "text")))
    (is (= "clojure" (get p "language")))
    (is (some? (get p "id")))
    (is (= [] (get p "symbols")))))

(deftest chunk->payload-type-is-correct-for-each-definition-type
  (is (= "function" (get (#'sut/chunk->payload (make-chunk {:chunk/type :fn})) "type")))
  (is (= "macro" (get (#'sut/chunk->payload (make-chunk {:chunk/type :macro})) "type")))
  (is (= "protocol" (get (#'sut/chunk->payload (make-chunk {:chunk/type :protocol})) "type")))
  (is (= "record" (get (#'sut/chunk->payload (make-chunk {:chunk/type :record})) "type")))
  (is (= "val" (get (#'sut/chunk->payload (make-chunk {:chunk/type :val})) "type")))
  (is (= "ns" (get (#'sut/chunk->payload (make-chunk {:chunk/type :ns})) "type"))))

(deftest chunk->payload-has-same-structure-for-all-types
  (let [keys-fn (fn [overrides]
                  (into (sorted-set) (keys (#'sut/chunk->payload (make-chunk overrides)))))]
    (is (= (keys-fn {:chunk/type :fn})
           (keys-fn {:chunk/type :macro}))
        "All definition types have identical payload keys")
    (is (= (keys-fn {:chunk/type :fn})
           (keys-fn {:chunk/type :protocol})))
    (is (= (keys-fn {:chunk/type :fn})
           (keys-fn {:chunk/type :record})))
    (is (= (keys-fn {:chunk/type :fn})
           (keys-fn {:chunk/type :val})))
    (is (= (keys-fn {:chunk/type :fn})
           (keys-fn {:chunk/type :ns})))))

;; ---------------------------------------------------------------------------
;; point->chunk roundtrip
;; ---------------------------------------------------------------------------

(deftest point->chunk-roundtrip
  (let [chunk (make-chunk {:chunk/ns "bank.user"
                           :chunk/name "create-user"
                           :chunk/type :fn
                           :chunk/visibility :public
                           :chunk/file "src/bank/user.clj"
                           :chunk/start-line 81
                           :chunk/end-line 120
                           :chunk/source "(defn create-user [name] ...)"
                           :chunk/hash "6d0b9d7..."
                           :chunk/language "clojure"})
        embedding [0.1 -0.2 0.3]
        point (#'sut/make-point chunk embedding)
        roundtripped (#'sut/point->chunk point)]
    (is (= (:chunk/id chunk) (:chunk/id roundtripped)))
    (is (= (:chunk/source chunk) (:chunk/source roundtripped)))
    (is (= (:chunk/language chunk) (:chunk/language roundtripped)))
    (is (= (:chunk/ns chunk) (:chunk/ns roundtripped)))
    (is (= (:chunk/name chunk) (:chunk/name roundtripped)))
    (is (= (:chunk/type chunk) (:chunk/type roundtripped)))
    (is (= (:chunk/visibility chunk) (:chunk/visibility roundtripped)))
    (is (= (:chunk/file chunk) (:chunk/file roundtripped)))
    (is (= (:chunk/start-line chunk) (:chunk/start-line roundtripped)))
    (is (= (:chunk/end-line chunk) (:chunk/end-line roundtripped)))
    (is (= (:chunk/hash chunk) (:chunk/hash roundtripped)))))

(deftest point->chunk-roundtrip-preserves-symbols
  (let [symbols #{'clojure.string/join 'bank.user/get-user 'mapv}
        chunk (make-chunk {:chunk/name "create-user"
                           :chunk/symbols symbols})
        embedding [0.1 -0.2 0.3]
        point (#'sut/make-point chunk embedding)
        roundtripped (#'sut/point->chunk point)]
    (is (= symbols (:chunk/symbols roundtripped)))))

(deftest point->chunk-roundtrip-with-nil-symbols
  (let [chunk (dissoc (make-chunk {:chunk/name "no-sym"}) :chunk/symbols)
        embedding [0.1 -0.2 0.3]
        point (#'sut/make-point chunk embedding)
        roundtripped (#'sut/point->chunk point)]
    (is (= #{} (:chunk/symbols roundtripped)))))

;; ---------------------------------------------------------------------------
;; make-point
;; ---------------------------------------------------------------------------

(deftest make-point-contains-id-vector-and-payload
  (let [chunk (make-chunk {:chunk/name "my-func"})
        embedding [0.1 0.2 0.3]
        point (#'sut/make-point chunk embedding)]
    (is (= (str (:chunk/id chunk)) (:id point)))
    (is (= embedding (:vector point)))
    (is (map? (:payload point)))
    (is (= "my-func" (get-in point [:payload "symbol"])))))

;; ---------------------------------------------------------------------------
;; create-collection! — Qdrant unavailable
;; ---------------------------------------------------------------------------

(deftest create-collection-fails-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (thrown? Exception (sut/create-collection! cfg))
        "Should throw when Qdrant is unreachable")))

;; ---------------------------------------------------------------------------
;; upsert! — Qdrant unavailable
;; ---------------------------------------------------------------------------

(deftest upsert-fails-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)
        chunks [(make-chunk {})]
        embeddings [[0.1 0.2 0.3]]]
    (is (thrown? Exception (sut/upsert! cfg chunks embeddings))
        "Should throw when Qdrant is unreachable")))

;; ---------------------------------------------------------------------------
;; delete! — Qdrant unavailable
;; ---------------------------------------------------------------------------

(deftest delete-fails-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (thrown? Exception (sut/delete! cfg "/fake/path.clj"))
        "Should throw when Qdrant is unreachable")))

;; ---------------------------------------------------------------------------
;; search — Qdrant unavailable
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; collection-info — Qdrant unavailable
;; ---------------------------------------------------------------------------

(deftest collection-info-returns-nil-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (nil? (sut/collection-info cfg))
        "Should return nil when Qdrant is unreachable")))

;; ---------------------------------------------------------------------------
;; scroll-all — Qdrant unavailable
;; ---------------------------------------------------------------------------

(deftest scroll-all-returns-nil-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (nil? (sut/scroll-all cfg))
        "Should return nil when Qdrant is unreachable")))

(deftest search-fails-when-qdrant-unavailable
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (thrown? Exception (sut/search cfg [0.1 0.2 0.3] 10))
        "Should throw when Qdrant is unreachable")))

;; ---------------------------------------------------------------------------
;; upsert! partitions batches
;; ---------------------------------------------------------------------------

(deftest upsert-partitions-large-batches
  (let [cfg (assoc test-config :qdrant/port 19999)
        chunks (repeatedly 250 #(make-chunk {}))
        embeddings (repeatedly 250 #(vector (rand) (rand) (rand)))]
    (is (thrown? Exception (sut/upsert! cfg chunks embeddings))
        "Should attempt at least 3 HTTP calls (250 / 100 = 3 batches)")))

;; ---------------------------------------------------------------------------
;; Integration with parser: payload from real chunks
;; ---------------------------------------------------------------------------

(deftest payload-from-parsed-chunk-has-expected-structure
  (let [fixtures-dir "test-resources/fixtures"
        result (parser/parse-file (str fixtures-dir "/valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        fn-chunk (first (filter #(= "create-user" (:chunk/name %))
                                (:result/chunks enriched)))
        embedding [0.1 -0.2 0.3]
        point (#'sut/make-point fn-chunk embedding)
        p (:payload point)]
    (is (some? (get p "id")))
    (is (= (str (:chunk/id fn-chunk)) (get p "id")))
    (is (= "clojure" (get p "language")))
    (is (= "fixtures.valid-defs" (get p "namespace")))
    (is (= "create-user" (get p "symbol")))
    (is (= "function" (get p "type")))
    (is (= "public" (get p "visibility")))
    (is (s/ends-with? (get p "file") "test-resources/fixtures/valid_defs.clj"))
    (is (number? (get p "start_line")))
    (is (number? (get p "end_line")))
    (is (string? (get p "hash")))))

;; ---------------------------------------------------------------------------
;; Edge cases: payload with nil fields
;; ---------------------------------------------------------------------------

(deftest chunk->payload-with-nil-ns
  (let [chunk (make-chunk {:chunk/ns nil :chunk/name nil})
        p (#'sut/chunk->payload chunk)]
    (is (nil? (get p "namespace")))
    (is (nil? (get p "symbol")))))

(deftest chunk->payload-with-all-type-mappings
  (is (= "function" (get (#'sut/chunk->payload (make-chunk {:chunk/type :fn})) "type")))
  (is (= "macro" (get (#'sut/chunk->payload (make-chunk {:chunk/type :macro})) "type")))
  (is (= "protocol" (get (#'sut/chunk->payload (make-chunk {:chunk/type :protocol})) "type")))
  (is (= "record" (get (#'sut/chunk->payload (make-chunk {:chunk/type :record})) "type")))
  (is (= "val" (get (#'sut/chunk->payload (make-chunk {:chunk/type :val})) "type")))
  (is (= "ns" (get (#'sut/chunk->payload (make-chunk {:chunk/type :ns})) "type")))
  (is (= "top-level" (get (#'sut/chunk->payload (make-chunk {:chunk/type :top-level})) "type")))
  (is (= "multimethod" (get (#'sut/chunk->payload (make-chunk {:chunk/type :multimethod})) "type"))))

;; ---------------------------------------------------------------------------
;; Edge cases: point->chunk roundtrip for different types
;; ---------------------------------------------------------------------------

(deftest point->chunk-roundtrip-all-types
  (doseq [type [:fn :macro :protocol :record :val :ns :top-level]]
    (let [chunk (make-chunk {:chunk/type type})
          embedding [0.1 0.2 0.3]
          point (#'sut/make-point chunk embedding)
          roundtripped (#'sut/point->chunk point)]
      (is (= (:chunk/type chunk) (:chunk/type roundtripped))
          (str "Type mismatch for " type)))))

;; ---------------------------------------------------------------------------
;; Edge cases: partition-all behavior for upsert
;; ---------------------------------------------------------------------------

(deftest upsert-partitions-into-correct-number-of-batches
  (let [cfg (assoc test-config :qdrant/port 19999)
        chunks (repeatedly 320 #(make-chunk {}))
        embeddings (repeatedly 320 #(vector (rand) (rand) (rand)))]
    (is (thrown? Exception (sut/upsert! cfg chunks embeddings))
        "Should attempt 4 HTTP calls (320 / 100 = 4 batches)")))

(deftest upsert-single-chunk
  (let [cfg (assoc test-config :qdrant/port 19999)
        chunks [(make-chunk {})]
        embeddings [[0.1 0.2 0.3]]]
    (is (thrown? Exception (sut/upsert! cfg chunks embeddings))
        "Should throw when Qdrant is unreachable even for single chunk")))

;; ---------------------------------------------------------------------------
;; Edge cases: update-file-path! with nil/new paths
;; ---------------------------------------------------------------------------

(deftest update-file-path-throws-on-qdrant-error
  (let [cfg (assoc test-config :qdrant/port 19999)]
    (is (thrown? Exception (sut/update-file-path! cfg "/old.clj" "/new.clj")))))