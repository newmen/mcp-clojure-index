(ns mcp.embeddings-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [mcp.embeddings :as sut]
            [clojure.data.json :as json]
            [mcp.config :as config])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(set! *warn-on-reflection* true)

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
;; rerank: a /v1/rerank server (llama.cpp `llama-server --rerank`)
;; ---------------------------------------------------------------------------

(defn- with-rerank-server
  "Calls `f` with the base URL of a local HTTP server whose `/rerank`
   answers `status` and `response-body`, recording the request bodies in
   `requests`.

  Atom [Map] -> Long -> Map -> (String -> IO a) -> IO a"
  [requests status response-body f]
  (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)]
    (.createContext server "/v1/rerank"
                    (reify HttpHandler
                      (handle [_this http-exchange]
                        (let [^HttpExchange exchange http-exchange
                              ^bytes body (.getBytes ^String (json/write-str response-body) "UTF-8")]
                          (swap! requests conj (json/read-str (slurp (.getRequestBody exchange))
                                                              :key-fn keyword))
                          (.sendResponseHeaders exchange (int status) (long (alength body)))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    (try
      (f (str "http://localhost:" (.getPort (.getAddress server)) "/v1"))
      (finally
        (.stop server 0)))))

(def ^:private candidates
  [{:text "order total" :unit {:id "a"}}
   {:text "connection pool" :unit {:id "b"}}
   {:text "apply discount" :unit {:id "c"}}])

(deftest rerank-sends-the-query-and-candidate-texts
  (let [requests (atom [])]
    (with-rerank-server requests 200 {:results []}
      (fn [url]
        (sut/rerank "sum an order" candidates
                    (assoc config/defaults :reranker/url url :reranker/model "bce"))))
    (is (= [{:model "bce"
             :query "sum an order"
             :documents ["order total" "connection pool" "apply discount"]}]
           @requests))))

(deftest rerank-sends-only-the-start-of-long-texts
  ;; the BCE model reads at most 512 tokens of query and text;
  ;; 1000 characters of code stay below
  (let [requests (atom [])
        long-text (apply str (repeat 1500 "x"))]
    (with-rerank-server requests 200 {:results []}
      (fn [url]
        (sut/rerank "a query" [{:text long-text}] (assoc config/defaults :reranker/url url))))
    (is (= [(subs long-text 0 993)] (:documents (first @requests))))))

(defn- rounded
  "The `reranked` Candidates with `:re-rank` rounded to three decimals.

  [Candidate] -> [Candidate]"
  [reranked]
  (mapv #(update % :re-rank (fn [score] (/ (Math/round (* 1000.0 score)) 1000.0))) reranked))

(deftest rerank-orders-candidates-by-relevance
  ;; llama-server answers logits; the relevance is their sigmoid, in 0..1
  (with-rerank-server (atom []) 200 {:results [{:index 2 :relevance_score 0.0}
                                               {:index 0 :relevance_score (Math/log 3)}
                                               {:index 1 :relevance_score (- (Math/log 3))}]}
    (fn [url]
      (is (= [(assoc (nth candidates 0) :re-rank 0.75)
              (assoc (nth candidates 2) :re-rank 0.5)
              (assoc (nth candidates 1) :re-rank 0.25)]
             (rounded (sut/rerank "sum an order" candidates (assoc config/defaults :reranker/url url))))))))

(deftest rerank-error-keeps-candidates-without-relevance-score
  (with-rerank-server (atom []) 500 {:error "model failed"}
    (fn [url]
      (is (= candidates
             (sut/rerank "sum an order" candidates (assoc config/defaults :reranker/url url)))))))

(deftest rerank-answer-without-a-score-for-each-candidate-keeps-candidates
  (doseq [results [[{:index 0 :relevance_score 0.5} {:index 1 :relevance_score 0.5}]
                   [{:index 0 :relevance_score 0.5} {:index 1 :relevance_score nil}
                    {:index 2 :relevance_score 0.5}]
                   [{:index 0 :relevance_score 0.5} {:index 0 :relevance_score 0.5}
                    {:index 2 :relevance_score 0.5}]]]
    (with-rerank-server (atom []) 200 {:results results}
      (fn [url]
        (is (= candidates
               (sut/rerank "sum an order" candidates (assoc config/defaults :reranker/url url))))))))

(deftest rerank-unavailable-keeps-candidates-without-relevance-score
  (is (= candidates
         (sut/rerank "sum an order" candidates
                     (assoc config/defaults :reranker/url "http://localhost:19999/v1")))))

(deftest rerank-empty-candidates
  (is (= [] (sut/rerank "test" [] config/defaults))))

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
;; embed-units
;; ---------------------------------------------------------------------------

(defn- temp-cache-config
  "The default config with a fresh temporary embedding cache directory.

  () -> IO Config"
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

