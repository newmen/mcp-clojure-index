(ns mcp.embeddings-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [mcp.embeddings :as sut]
            [mcp.config :as config]))

;; ---------------------------------------------------------------------------
;; Embedding cache
;; ---------------------------------------------------------------------------

(deftest cache-roundtrip
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache")
        hash-str "testhash123"
        embedding [0.1 -0.2 0.3]]
    (try
      (sut/cache-embedding! cfg hash-str embedding)
      (is (some? (sut/cached-embedding cfg hash-str)))
      (is (= embedding (sut/cached-embedding cfg hash-str)))
      (finally
        (io/delete-file (io/file "/tmp/mcp-test-cache" (str hash-str ".edn")) true)
        (io/delete-file (io/file "/tmp/mcp-test-cache") true)))))

(deftest cache-returns-nil-for-missing-hash
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache-miss")]
    (is (nil? (sut/cached-embedding cfg "nonexistent-hash")))))

(deftest cache-works-with-root-path-format
  (let [root-path "/tmp/mcp-embedding-root-test"
        cache-dir (str root-path "/.mcp/embedding-cache")
        cfg (assoc config/defaults :embedding/cache-dir cache-dir)
        hash-str "diroottest123"
        embedding [0.42 0.99]]
    (try
      (sut/cache-embedding! cfg hash-str embedding)
      (is (some? (sut/cached-embedding cfg hash-str)))
      (is (= embedding (sut/cached-embedding cfg hash-str)))
      (finally
        (let [mcp-dir (io/file root-path ".mcp")]
          (when (.exists mcp-dir)
            (doseq [f (reverse (file-seq mcp-dir))]
              (io/delete-file f true))))))))

(deftest cache-embedding-roundtrip
  (let [root-path "/tmp/mcp-embedding-roundtrip"
        cache-dir (str root-path "/.mcp/embedding-cache")
        cfg (assoc config/defaults :embedding/cache-dir cache-dir)
        hash-str "roundtriphash001"
        embedding [0.1 0.2 0.3 0.4 0.5]]
    (try
      (sut/cache-embedding! cfg hash-str embedding)
      (is (= embedding (sut/cached-embedding cfg hash-str)))
      (sut/cache-embedding! cfg hash-str [0.9 0.8])
      (is (= [0.9 0.8] (sut/cached-embedding cfg hash-str)))
      (finally
        (let [mcp-dir (io/file root-path ".mcp")]
          (when (.exists mcp-dir)
            (doseq [f (reverse (file-seq mcp-dir))]
              (io/delete-file f true))))))))

(deftest cache-uses-consistent-filenames
  (let [cfg1 (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache2")
        cfg2 (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache2")
        hash-str "consistent123"
        embedding [0.5 0.5]]
    (try
      (sut/cache-embedding! cfg1 hash-str embedding)
      (is (= embedding (sut/cached-embedding cfg2 hash-str)))
      (finally
        (io/delete-file (io/file "/tmp/mcp-test-cache2" (str hash-str ".edn")) true)
        (io/delete-file (io/file "/tmp/mcp-test-cache2") true)))))

;; ---------------------------------------------------------------------------
;; generate interface — architecture spec: (generate texts)
;; ---------------------------------------------------------------------------

(deftest generate-empty-texts-returns-nil
  (is (nil? (sut/generate []))))

(deftest generate-throws-when-ollama-unreachable
  (binding [sut/*config* (assoc config/defaults :ollama/url "http://localhost:19999")]
    (is (thrown? Exception (sut/generate ["test"])))))

;; ---------------------------------------------------------------------------
;; rerank interface — architecture spec: (rerank query candidates)
;; ---------------------------------------------------------------------------

(deftest rerank-empty-candidates
  (is (= [] (sut/rerank "test" []))))

(deftest rerank-fallback-on-error
  (let [candidates [{:text "hello" :score 0.5} {:text "world" :score 0.3}]]
    (binding [sut/*config* (assoc config/defaults :ollama/url "http://localhost:19999")]
      (let [result (sut/rerank "test query" candidates)]
        (is (= 2 (count result)))
        (is (every? #(contains? % :re-rank) result))
        (is (every? #(= (:score %) (:re-rank %)) result))))))

;; ---------------------------------------------------------------------------
;; dynamic *config* binding works
;; ---------------------------------------------------------------------------

(deftest dynamic-config-override-works
  (binding [sut/*config* {:ollama/url "http://localhost:19999"
                          :ollama/embedding-model "test-model"}]
    (is (thrown? Throwable (sut/generate ["hello"]))
        "Should attempt to connect to overridden URL")))

(deftest generate-with-explicit-config-still-works
  (let [cfg (assoc config/defaults :ollama/url "http://localhost:29999")]
    (is (thrown? Throwable (sut/generate ["hello"] cfg))
        "Should attempt to connect to explicit config URL")))

;; ---------------------------------------------------------------------------
;; Edge cases: cache with concurrent access simulation
;; ---------------------------------------------------------------------------

(deftest cache-handles-concurrent-writes
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache-concurrent")
        hashes (mapv #(str "concurrent-" %) (range 50))
        embeddings (mapv (fn [i] [(double i) (double (* i 2))]) (range 50))]
    (try
      (run! (fn [[h e]] (sut/cache-embedding! cfg h e)) (map vector hashes embeddings))
      (doseq [[h e] (map vector hashes embeddings)]
        (is (= e (sut/cached-embedding cfg h))
            (str "Cache mismatch for " h)))
      (finally
        (doseq [^java.io.File f (.listFiles (io/file "/tmp/mcp-test-cache-concurrent"))]
          (io/delete-file f true))
        (io/delete-file (io/file "/tmp/mcp-test-cache-concurrent") true)))))

;; ---------------------------------------------------------------------------
;; Edge cases: rerank with single candidate
;; ---------------------------------------------------------------------------

(deftest rerank-single-candidate-works
  (let [candidates [{:text "some code" :score 0.9}]]
    (is (= 1 (count (sut/rerank "test" candidates))))))

(deftest rerank-very-long-query
  (let [long-query (apply str (repeat 100 "query "))
        candidates [{:text "short code" :score 0.5} {:text "longer code" :score 0.4}]]
    (is (= 2 (count (sut/rerank long-query candidates))))))

;; ---------------------------------------------------------------------------
;; embed-units
;; ---------------------------------------------------------------------------

(defn- temp-cache-config
  []
  (assoc config/defaults
         :embedding/cache-dir (str (java.nio.file.Files/createTempDirectory
                                    "embed-units-test"
                                    (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest embed-units-sends-only-uncached-texts-to-ollama
  (let [cfg (temp-cache-config)
        sent (atom [])
        units [{:hash "h-a" :text "a"} {:hash "h-b" :text "b"} {:hash "h-c" :text "c"}]]
    (sut/cache-embedding! cfg "h-a" [0.1 0.2])
    (sut/cache-embedding! cfg "h-c" [0.3 0.4])
    (with-redefs [sut/generate (fn [texts _cfg] (swap! sent into texts) (mapv (constantly [0.5 0.6]) texts))]
      (is (= [[0.1 0.2] [0.5 0.6] [0.3 0.4]] (sut/embed-units cfg units))))
    (is (= ["b"] @sent))
    (testing "the generated vector is cached"
      (is (= [0.5 0.6] (sut/cached-embedding cfg "h-b"))))))

(deftest embed-units-of-no-units-is-empty
  (is (= [] (sut/embed-units (temp-cache-config) []))))

