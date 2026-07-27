(ns mcp.embeddings-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [mcp.embeddings :as sut]
            [mcp.parser :as parser]
            [mcp.config :as config]))

(def fixtures-dir "test-resources/fixtures")

(defn fixture-path
  [name]
  (str fixtures-dir "/" name))

;; ---------------------------------------------------------------------------
;; build-embedding-text
;; ---------------------------------------------------------------------------

(defn- make-chunk
  [overrides]
  (merge
    {:chunk/id        (java.util.UUID/randomUUID)
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

(deftest build-embedding-text-includes-language
  (let [text (sut/build-embedding-text (make-chunk {}))]
    (is (.contains text "Language:"))
    (is (.contains text "Clojure"))))

(deftest build-embedding-text-includes-namespace
  (let [text (sut/build-embedding-text (make-chunk {:chunk/ns "bank.user"}))]
    (is (.contains text "Namespace:"))
    (is (.contains text "bank.user"))))

(deftest build-embedding-text-includes-symbol
  (let [text (sut/build-embedding-text (make-chunk {:chunk/name "create-user"}))]
    (is (.contains text "Symbol:"))
    (is (.contains text "create-user"))))

(deftest build-embedding-text-includes-docstring
  (let [text (sut/build-embedding-text
               (make-chunk {:chunk/metadata {:doc "Creates a user."}}))]
    (is (.contains text "Docstring:"))
    (is (.contains text "Creates a user."))))

(deftest build-embedding-text-omits-docstring-when-missing
  (let [text (sut/build-embedding-text (make-chunk {:chunk/metadata {}}))]
    (is (not (.contains text "Docstring:")))))

(deftest build-embedding-text-no-extra-blank-lines-without-docstring
  (let [text (sut/build-embedding-text (make-chunk {}))
        lines (s/split-lines text)
        blank-runs (->> lines
                        (partition-by #(= "" %))
                        (filter #(= "" (first %))))]
    (is (every? #(= 1 (count %)) blank-runs)
        "Should not have consecutive blank lines")))

(deftest build-embedding-text-includes-code
  (let [source "(defn my-func [x] (* x 2))"
        text (sut/build-embedding-text (make-chunk {:chunk/source source}))]
    (is (.contains text "Code:"))
    (is (.contains text source))))

(deftest build-embedding-text-handles-nil-ns
  (let [text (sut/build-embedding-text (make-chunk {:chunk/ns nil}))]
    (is (.contains text "Namespace:"))
    (is (not (nil? text)))))

(deftest build-embedding-text-handles-nil-name
  (let [text (sut/build-embedding-text (make-chunk {:chunk/name nil}))]
    (is (.contains text "Symbol:"))
    (is (not (nil? text)))))

(deftest build-embedding-text-from-parsed-chunk
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        fn-chunk (first (filter #(= "create-user" (:chunk/name %))
                                 (:result/chunks enriched)))
        text (sut/build-embedding-text fn-chunk)]
    (is (.contains text "Namespace:"))
    (is (.contains text "fixtures.valid-defs"))
    (is (.contains text "Symbol:"))
    (is (.contains text "create-user"))
    (is (.contains text "Docstring:"))
    (is (.contains text "Creates a new user in the system."))
    (is (.contains text "(defn create-user"))
    (is (.contains text "Code:"))))

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
;; generate-from-chunks — all cached
;; ---------------------------------------------------------------------------

(deftest generate-from-chunks-skips-cached
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache3")
        result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        chunks (:result/chunks enriched)]
    (try
      (run! (fn [c] (sut/cache-embedding! cfg (:chunk/hash c) [(double (.hashCode (:chunk/name c)))]))
            chunks)
      (let [pairs (sut/generate-from-chunks cfg chunks)]
        (is (= (count chunks) (count pairs)))
        (is (every? vector? (map second pairs))))
      (finally
        (doseq [^java.io.File f (.listFiles (io/file "/tmp/mcp-test-cache3"))]
          (io/delete-file f true))
        (io/delete-file (io/file "/tmp/mcp-test-cache3") true)))))

(deftest generate-from-chunks-returns-all-pairs
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache4")
        chunks [(make-chunk {:chunk/hash "h1" :chunk/source "(defn a [x] x)"})
                (make-chunk {:chunk/hash "h2" :chunk/source "(defn b [x] x)"})
                (make-chunk {:chunk/hash "h3" :chunk/source "(defn c [x] x)"})]]
    (try
      (run! (fn [c] (sut/cache-embedding! cfg (:chunk/hash c) [(double (.hashCode (:chunk/name c)))]))
            chunks)
      (let [pairs (sut/generate-from-chunks cfg chunks)]
        (is (= 3 (count pairs)))
        (is (every? vector? (map second pairs))))
      (finally
        (doseq [^java.io.File f (.listFiles (io/file "/tmp/mcp-test-cache4"))]
          (io/delete-file f true))
        (io/delete-file (io/file "/tmp/mcp-test-cache4") true)))))

;; ---------------------------------------------------------------------------
;; generate-from-chunks — mixed cache: some cached, some not
;; ---------------------------------------------------------------------------

(deftest generate-from-chunks-mixed-cache-works
  (let [cfg (assoc config/defaults :embedding/cache-dir "/tmp/mcp-test-cache5")
        chunks [(make-chunk {:chunk/hash "pre-cached-1" :chunk/name "a"
                             :chunk/source "(defn a [x] x)"})
                (make-chunk {:chunk/hash "uncached-1" :chunk/name "b"
                             :chunk/source "(defn b [x] x)"})
                (make-chunk {:chunk/hash "pre-cached-2" :chunk/name "c"
                             :chunk/source "(defn c [x] x)"})]]
    (try
      (sut/cache-embedding! cfg "pre-cached-1" [0.1 0.2])
      (sut/cache-embedding! cfg "pre-cached-2" [0.3 0.4])
      (let [result (sut/generate-from-chunks cfg chunks)]
        (is (= 3 (count result)) "Should return 3 pairs")
        (is (= [0.1 0.2] (second (first result))) "First chunk uses cached")
        (is (= [0.3 0.4] (second (nth result 2))) "Third chunk uses cached")
        (is (vector? (second (second result))) "Second chunk generates real embedding"))
      (finally
        (doseq [^java.io.File f (.listFiles (io/file "/tmp/mcp-test-cache5"))]
          (io/delete-file f true))
        (io/delete-file (io/file "/tmp/mcp-test-cache5") true)))))

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