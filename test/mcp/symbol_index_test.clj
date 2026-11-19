(ns mcp.symbol-index-test
  (:require [clojure.test :refer :all]
            [mcp.symbol-index :as sut]
            [mcp.parser :as parser]))

(set! *warn-on-reflection* true)

(def fixtures-dir "test-resources/fixtures")

(defn fixture-path
  [name]
  (str fixtures-dir "/" name))

(defn- parse-and-index
  [fixture-name]
  (let [result (parser/parse-file (fixture-path fixture-name))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)]
    (sut/build-index symbols chunks)))

;; ---------------------------------------------------------------------------
;; empty-index
;; ---------------------------------------------------------------------------

(deftest empty-index-has-all-keys
  (let [idx (sut/empty-index)]
    (is (map? (:index/by-qname idx)))
    (is (map? (:index/by-simple idx)))
    (is (map? (:index/by-file idx)))
    (is (map? (:index/namespaces idx)))))

;; ---------------------------------------------------------------------------
;; build-index
;; ---------------------------------------------------------------------------

(deftest build-index-from-valid-defs
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= 9 (count (:index/by-qname idx))))
    (is (some? (get (:index/by-qname idx) 'fixtures.valid-defs/create-user)))
    (is (some? (get (:index/by-qname idx) 'fixtures.valid-defs/UserProtocol)))
    (is (some? (get (:index/by-qname idx) 'fixtures.valid-defs/MAX-RETRIES)))))

(deftest build-index-symbols-have-all-fields
  (let [idx (parse-and-index "valid_defs.clj")
        sym (get (:index/by-qname idx) 'fixtures.valid-defs/create-user)]
    (is (some? sym))
    (is (= 'fixtures.valid-defs/create-user (:sym/name sym)))
    (is (= 'create-user (:sym/simple sym)))
    (is (= 'fixtures.valid-defs (:sym/ns sym)))
    (is (= :fn (:sym/type sym)))
    (is (= :public (:sym/visibility sym)))
    (is (string? (:sym/file sym)))
    (is (pos? (:sym/line sym)))
    (is (uuid? (:sym/chunk-id sym)))))

(deftest build-index-private-visibility
  (let [idx (parse-and-index "valid_defs.clj")
        sym (get (:index/by-qname idx) 'fixtures.valid-defs/format-name)]
    (is (= :private (:sym/visibility sym)))))

(deftest build-index-by-simple
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (contains? (:index/by-simple idx) 'create-user))
    (is (contains? (:index/by-simple idx) 'UserProtocol))
    (is (contains? (:index/by-simple idx) 'MAX-RETRIES))))

(deftest build-index-by-file
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= 1 (count (:index/by-file idx))))
    (is (every? #(.endsWith ^String % "valid_defs.clj")
                (keys (:index/by-file idx))))))

;; ---------------------------------------------------------------------------
;; remove-file!
;; ---------------------------------------------------------------------------

(deftest remove-file-removes-symbols
  (let [idx (parse-and-index "valid_defs.clj")
        file-path (-> idx :index/by-file keys first)
        removed (sut/remove-file! idx file-path)]
    (is (empty? (:index/by-qname removed)))
    (is (empty? (:index/by-simple removed)))
    (is (empty? (:index/by-file removed)))))

(deftest remove-file-noop-for-unknown-file
  (let [idx (parse-and-index "valid_defs.clj")
        removed (sut/remove-file! idx "/nonexistent.clj")]
    (is (= idx removed))))

;; ---------------------------------------------------------------------------
;; add-file!
;; ---------------------------------------------------------------------------

(deftest add-file-adds-symbols
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        idx (sut/empty-index)
        added (sut/add-file! idx symbols)]
    (is (seq (:index/by-qname added)))
    (is (seq (:index/by-simple added)))
    (is (= 1 (count (:index/by-file added))))
    (is (some? (get (:index/by-qname added) 'fixtures.valid-defs/create-user)))))

(deftest add-file-replaces-existing-symbols
  (let [idx (parse-and-index "valid_defs.clj")
        result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        readded (sut/add-file! idx symbols)]
    (is (= (count (:index/by-qname idx))
           (count (:index/by-qname readded))))))

(deftest add-file-noop-for-empty
  (let [idx (sut/empty-index)
        added (sut/add-file! idx [])]
    (is (= idx added))))

;; ---------------------------------------------------------------------------
;; Multi-namespace project
;; ---------------------------------------------------------------------------

(deftest multi-namespace-index
  (let [raw-result (parser/parse-project (str fixtures-dir "/multi_ns") [])
        enriched (parser/enrich-project-chunks raw-result)
        by-file (group-by :chunk/file (:chunks enriched))
        all-symbols (into []
                          (mapcat (fn [[_file chunks]]
                                    (let [ns-name (:chunk/ns (first chunks))]
                                      (keep #(parser/chunk->symbol-record % ns-name) chunks))))
                          by-file)
        all-chunks (:chunks enriched)
        idx (sut/build-index all-symbols all-chunks)]
    (is (= 5 (count (:index/by-qname idx))))
    (is (some? (get (:index/by-qname idx) 'multi-ns.core/process)))
    (is (some? (get (:index/by-qname idx) 'multi-ns.utils/enrich)))
    (is (some? (get (:index/by-qname idx) 'multi-ns.utils/validate)))
    (is (= 2 (count (:index/namespaces idx))))))

;; ---------------------------------------------------------------------------
;; Metadata extraction (Task 4)
;; ---------------------------------------------------------------------------

(deftest metadata-has-docstring
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        create-user (first (filter #(= "create-user" (:chunk/name %))
                                    (:result/chunks enriched)))
        meta-data (:chunk/metadata create-user)]
    (is (= "Creates a new user in the system." (:doc meta-data)))))

(deftest metadata-has-arglists
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        create-user (first (filter #(= "create-user" (:chunk/name %))
                                    (:result/chunks enriched)))
        meta-data (:chunk/metadata create-user)]
    (is (= '[name email] (:arglists meta-data)))))

(deftest metadata-private-fn-has-no-docstring
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        format-name (first (filter #(= "format-name" (:chunk/name %))
                                    (:result/chunks enriched)))
        meta-data (:chunk/metadata format-name)]
    (is (nil? (:doc meta-data)))
    (is (= '[name] (:arglists meta-data)))))

(deftest metadata-empty-for-dynamic-val
  (let [result (parser/parse-file (fixture-path "valid_defs.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        db-conn (first (filter #(= "*db-connection*" (:chunk/name %))
                                (:result/chunks enriched)))
        meta-data (:chunk/metadata db-conn)]
    (is (= {} meta-data))))

(deftest symbol-index-metadata-fields
  (let [idx (parse-and-index "valid_defs.clj")
        sym (get (:index/by-qname idx) 'fixtures.valid-defs/create-user)]
    (is (= "Creates a new user in the system." (:sym/doc sym)))
    (is (seq (:sym/arglists sym)))))

;; ---------------------------------------------------------------------------
;; Edge cases: large project index
;; ---------------------------------------------------------------------------

(deftest build-index-from-large-file
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        idx (sut/build-index symbols chunks)]
    (is (pos? (count (:index/by-qname idx))))
    (is (pos? (count (:index/by-simple idx))))
    (is (= 1 (count (:index/by-file idx))))
    (is (some? (get (:index/by-qname idx) 'fixtures.large-file/func-1)))
    (is (some? (get (:index/by-qname idx) 'fixtures.large-file/aggregate)))
    (is (some? (get (:index/by-qname idx) 'fixtures.large-file/Serializer)))
    (is (some? (get (:index/by-qname idx) 'fixtures.large-file/UserRecord)))))

(deftest build-index-large-file-no-gaps
  (let [result (parser/parse-file (fixture-path "large_file.clj"))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        idx (sut/build-index symbols chunks)]
    (is (= (count symbols) (count (:index/by-qname idx))))))

;; ---------------------------------------------------------------------------
;; Edge cases: remove-file! idempotency
;; ---------------------------------------------------------------------------

(deftest remove-file-twice-idempotent
  (let [idx (parse-and-index "valid_defs.clj")
        file-path (-> idx :index/by-file keys first)
        removed1 (sut/remove-file! idx file-path)
        removed2 (sut/remove-file! removed1 file-path)]
    (is (= removed1 removed2))))

