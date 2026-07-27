(ns mcp.performance-test
  (:require [clojure.test :refer :all]
            [mcp.parser :as parser]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.embeddings :as embeddings]))

(set! *warn-on-reflection* true)

(def fixtures-dir "test-resources/fixtures")

(defn fixture-path
  [name]
  (str fixtures-dir "/" name))

;; ---------------------------------------------------------------------------
;; Performance metrics collection (non-benchmark, just timing checks)
;; These tests record execution times and verify they are within reasonable bounds.
;; For real benchmarks, use criterium.
;; ---------------------------------------------------------------------------

(deftest parse-large-file-under-5-seconds
  (let [start (System/nanoTime)
        result (parser/parse-file (fixture-path "large_file.clj"))
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (seq (:result/chunks result)))
    (is (< elapsed 5000)
        (str "Parsing large_file.clj took " elapsed "ms (limit: 5000ms)"))))

(deftest parse-valid-defs-under-200ms
  (let [start (System/nanoTime)
        result (parser/parse-file (fixture-path "valid_defs.clj"))
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (seq (:result/chunks result)))
    (is (< elapsed 200)
        (str "Parsing valid_defs.clj took " elapsed "ms"))))

(deftest build-index-large-file-under-3-seconds
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        start (System/nanoTime)
        idx (si/build-index symbols chunks)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (pos? (count (:index/by-qname idx))))
    (is (< elapsed 3000)
        (str "Building index for large file took " elapsed "ms"))))

(deftest build-graph-large-file-under-5-seconds
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        idx (si/build-index symbols chunks)
        start (System/nanoTime)
        g (graph/build-graph chunks idx)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (sequential? (:edges g)))
    (is (< elapsed 5000)
        (str "Building graph for large file took " elapsed "ms"))))

(deftest build-embedding-texts-100-chunks-under-200ms
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        chunks (take 100 (:result/chunks enriched))
        start (System/nanoTime)
        texts (mapv embeddings/build-embedding-text chunks)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (= 100 (count texts)))
    (is (< elapsed 200)
        (str "Building 100 embedding texts took " elapsed "ms"))))

(deftest full-pipeline-large-file-under-15-seconds
  (let [start (System/nanoTime)
        result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        chunks (:result/chunks enriched)
        symbols (parser/extract-symbols enriched)
        idx (si/build-index symbols chunks)
        g (graph/build-graph chunks idx)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (pos? (count (:index/by-qname idx))))
    (is (sequential? (:edges g)))
    (is (< elapsed 15000)
        (str "Full pipeline for large file took " elapsed "ms"))))

(deftest parse-project-multi-ns-under-500ms
  (let [start (System/nanoTime)
        result (parser/parse-project (str fixtures-dir "/multi_ns") [])
        enriched (parser/enrich-project-chunks result)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (seq (:chunks enriched)))
    (is (< elapsed 500)
        (str "Parse project multi_ns took " elapsed "ms"))))

;; ---------------------------------------------------------------------------
;; Memory usage checks (approximate via available memory)
;; ---------------------------------------------------------------------------

(deftest large-file-chunks-fit-in-memory
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        chunks (:result/chunks result)
        total-source-bytes (reduce + (map (comp count :chunk/source) chunks))
        chunks-count (count chunks)]
    (is (> chunks-count 1800))
    (is (< total-source-bytes (* 10 1024 1024))
        (str "Total source bytes: " total-source-bytes " (limit: 10MB)"))))

(deftest large-file-symbols-fit-in-memory
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)]
    (is (pos? (count symbols)))
    (is (< (count symbols) 2500)
        (str "Symbol count: " (count symbols) " (limit: 2500)"))))

;; ---------------------------------------------------------------------------
;; Stability: repeated parses produce same results
;; ---------------------------------------------------------------------------

(deftest repeated-parses-identical-large-file
  (let [r1 (parser/parse-file (fixture-path "large_file.clj"))
        r2 (parser/parse-file (fixture-path "large_file.clj"))
        h1 (set (map (juxt :chunk/name :chunk/hash) (:result/chunks r1)))
        h2 (set (map (juxt :chunk/name :chunk/hash) (:result/chunks r2)))]
    (is (= h1 h2)
        "Repeated parses of large file should produce identical chunks")))

(deftest repeated-index-identical-large-file
  (let [parse #(let [p (parser/parse-file (fixture-path "large_file.clj"))
                     e (parser/enrich-chunks-with-ns p)
                     s (parser/extract-symbols e)
                     c (:result/chunks e)]
                 {:symbols s :chunks c})
        run1 (parse)
        run2 (parse)
        idx1 (si/build-index (:symbols run1) (:chunks run1))
        idx2 (si/build-index (:symbols run2) (:chunks run2))]
    (is (= (set (keys (:index/by-qname idx1)))
           (set (keys (:index/by-qname idx2)))))))

;; ---------------------------------------------------------------------------
;; Incremental indexing: add-file!/remove-file! on large index
;; ---------------------------------------------------------------------------

(deftest incremental-update-on-large-index
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        idx (si/build-index symbols chunks)
        file-path (-> idx :index/by-file keys first)
        start (System/nanoTime)
        removed (si/remove-file! idx file-path)
        remove-elapsed (/ (- (System/nanoTime) start) 1e6)]
    (is (empty? (:index/by-qname removed)))
    (is (< remove-elapsed 100)
        (str "remove-file! on large index took " remove-elapsed "ms"))
    (let [start2 (System/nanoTime)
          re-added (si/add-file! removed symbols)
          add-elapsed (/ (- (System/nanoTime) start2) 1e6)]
      (is (pos? (count (:index/by-qname re-added))))
      (is (< add-elapsed 100)
          (str "add-file! on large index took " add-elapsed "ms")))))

(deftest incremental-graph-update-on-large-file
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        idx (si/build-index symbols chunks)
        g (graph/build-graph chunks idx)
        file-path (first (keys (:by-file g)))]
    (is (pos? (count (:edges g))))
    (let [start (System/nanoTime)
          g-removed (graph/remove-file! g file-path)
          elapsed (/ (- (System/nanoTime) start) 1e6)]
      (is (< (count (:edges g-removed)) (count (:edges g))))
      (is (< elapsed 100)
          (str "graph remove-file! on large graph took " elapsed "ms")))))