(ns mcp.parser-test
  (:require [clojure.test :refer :all]
            [mcp.parser :as sut]))

(def fixtures-dir "test/mcp/fixtures")

(defn fixture-path
  [name]
  (str fixtures-dir "/" name))

;; ---------------------------------------------------------------------------
;; Basic file detection
;; ---------------------------------------------------------------------------

(deftest clojure-file-detection
  (is (sut/clojure-file? "foo.clj"))
  (is (sut/clojure-file? "foo.cljc"))
  (is (sut/clojure-file? "foo.cljs"))
  (is (not (sut/clojure-file? "foo.edn")))
  (is (not (sut/clojure-file? "foo.txt"))))

(deftest edn-file-detection
  (is (sut/edn-file? "config.edn"))
  (is (not (sut/edn-file? "foo.clj"))))

;; ---------------------------------------------------------------------------
;; valid_defs.clj
;; ---------------------------------------------------------------------------

(deftest parse-valid-defs-returns-chunks
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))]
    (is (seq (:result/chunks result)))
    (is (empty? (:result/errors result)))))

(deftest parse-valid-defs-has-ns-chunk
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        ns-chunk (first (filter #(= :ns (:chunk/type %)) (:result/chunks result)))]
    (is ns-chunk)
    (is (= "fixtures.valid-defs" (:chunk/name ns-chunk)))))

(deftest parse-valid-defs-has-defn
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        fn-chunks (filter #(= :fn (:chunk/type %)) (:result/chunks result))
        names (set (map :chunk/name fn-chunks))]
    (is (contains? names "create-user"))
    (is (contains? names "format-name"))))

(deftest parse-valid-defs-visibility
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        fn-chunks (filter #(= :fn (:chunk/type %)) (:result/chunks result))
        vis-map (into {} (map (juxt :chunk/name :chunk/visibility) fn-chunks))]
    (is (= :public (get vis-map "create-user")))
    (is (= :private (get vis-map "format-name")))))

(deftest parse-valid-defs-has-defmacro
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        macro-chunks (filter #(= :macro (:chunk/type %)) (:result/chunks result))]
    (is (= 1 (count macro-chunks)))
    (is (= "with-tx" (:chunk/name (first macro-chunks))))))

(deftest parse-valid-defs-has-defprotocol
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        protocol-chunks (filter #(= :protocol (:chunk/type %)) (:result/chunks result))]
    (is (= 1 (count protocol-chunks)))
    (is (= "UserProtocol" (:chunk/name (first protocol-chunks))))))

(deftest parse-valid-defs-has-defrecord
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        record-chunks (filter #(= :record (:chunk/type %)) (:result/chunks result))]
    (is (= 2 (count record-chunks)))
    (is (contains? (set (map :chunk/name record-chunks)) "User"))
    (is (contains? (set (map :chunk/name record-chunks)) "UserType"))))

(deftest parse-valid-defs-has-def-vars
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        var-chunks (filter #(= :var (:chunk/type %)) (:result/chunks result))
        names (set (map :chunk/name var-chunks))]
    (is (contains? names "MAX-RETRIES"))))

(deftest parse-valid-defs-line-numbers
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        ns-chunk (first (filter #(= :ns (:chunk/type %)) (:result/chunks result)))
        fn-chunk (first (filter #(= "create-user" (:chunk/name %)) (:result/chunks result)))]
    (is (pos? (:chunk/start-line ns-chunk)))
    (is (>= (:chunk/end-line ns-chunk) (:chunk/start-line ns-chunk)))
    (is (pos? (:chunk/start-line fn-chunk)))
    (is (>= (:chunk/end-line fn-chunk) (:chunk/start-line fn-chunk)))))

(deftest parse-valid-defs-hash
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        chunks (:result/chunks result)]
    (is (every? :chunk/hash chunks))
    (is (every? #(= 64 (count (:chunk/hash %))) chunks))))

(deftest parse-valid-defs-source-preserved
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        fn-chunk (first (filter #(= "create-user" (:chunk/name %)) (:result/chunks result)))]
    (is (.startsWith ^String (:chunk/source fn-chunk) "(defn create-user"))))

;; ---------------------------------------------------------------------------
;; syntax_error.clj — line-based parser does not detect syntax errors,
;; but still extracts partial forms without crashing
;; ---------------------------------------------------------------------------

(deftest parse-syntax-error-no-crash
  (let [result (sut/parse-file (fixture-path "syntax_error.clj"))]
    (is (some? result))
    (is (seq (:result/chunks result)))))

;; ---------------------------------------------------------------------------
;; empty.clj
;; ---------------------------------------------------------------------------

(deftest parse-empty-file
  (let [result (sut/parse-file (fixture-path "empty.clj"))]
    (is (empty? (:result/chunks result)))
    (is (empty? (:result/errors result)))))

;; ---------------------------------------------------------------------------
;; cljs_file.cljs
;; ---------------------------------------------------------------------------

(deftest parse-cljs-file
  (let [result (sut/parse-file (fixture-path "cljs_file.cljs"))]
    (is (seq (:result/chunks result)))
    (is (some #(= "greeting" (:chunk/name %)) (:result/chunks result)))))

;; ---------------------------------------------------------------------------
;; protocol_def.cljc
;; ---------------------------------------------------------------------------

(deftest parse-cljc-file
  (let [result (sut/parse-file (fixture-path "protocol_def.cljc"))]
    (is (seq (:result/chunks result)))
    (is (some #(= "Transferable" (:chunk/name %)) (:result/chunks result)))
    (is (some #(= "Comparable" (:chunk/name %)) (:result/chunks result)))))

;; ---------------------------------------------------------------------------
;; enrich-chunks-with-ns
;; ---------------------------------------------------------------------------

(deftest enrich-sets-ns
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        enriched (sut/enrich-chunks-with-ns result)]
    (is (every? (comp some? :chunk/ns) (:result/chunks enriched)))
    (is (= "fixtures.valid-defs" (:chunk/ns (first (:result/chunks enriched)))))))

;; ---------------------------------------------------------------------------
;; extract-symbols
;; ---------------------------------------------------------------------------

(deftest extract-symbols-from-valid-defs
  (let [result (sut/parse-file (fixture-path "valid_defs.clj"))
        enriched (sut/enrich-chunks-with-ns result)
        symbols (sut/extract-symbols enriched)]
    (is (seq symbols))
    (is (some #(= "fixtures.valid-defs/create-user" (str (:sym/name %))) symbols))
    (is (some #(= "fixtures.valid-defs/format-name" (str (:sym/name %))) symbols))
    (is (some #(= "fixtures.valid-defs/UserProtocol" (str (:sym/name %))) symbols))
    (is (some #(= "fixtures.valid-defs/User" (str (:sym/name %))) symbols))))

;; ---------------------------------------------------------------------------
;; parse-project
;; ---------------------------------------------------------------------------

(deftest parse-project-finds-multi-ns
  (let [result (sut/parse-project (str fixtures-dir "/multi_ns") [])]
    (is (seq (:chunks result)))
    (let [names (set (map :chunk/name (:chunks result)))]
      (is (contains? names "process"))
      (is (contains? names "enrich"))
      (is (contains? names "validate")))))

;; ---------------------------------------------------------------------------
;; EDN file
;; ---------------------------------------------------------------------------

(deftest parse-edn-file
  (let [result (sut/parse-file (fixture-path "config.edn"))]
    (is (seq (:result/chunks result)))
    (is (every? #(= :top-level (:chunk/type %)) (:result/chunks result)))))

;; ---------------------------------------------------------------------------
;; sha-256 utility
;; ---------------------------------------------------------------------------

(deftest sha-256-is-stable
  (is (= (sut/sha-256 "hello") (sut/sha-256 "hello")))
  (is (not= (sut/sha-256 "hello") (sut/sha-256 "world"))))

(deftest sha-256-length
  (is (= 64 (count (sut/sha-256 "anything")))))