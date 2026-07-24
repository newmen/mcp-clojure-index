(ns mcp.symbol-index-test
  (:require [clojure.test :refer :all]
            [mcp.symbol-index :as sut]
            [mcp.parser :as parser]))

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
    (is (map? (:index/by-type idx)))
    (is (map? (:index/namespaces idx)))))

(deftest empty-index-by-type-all-types
  (let [idx (sut/empty-index)]
    (is (contains? (:index/by-type idx) :fn))
    (is (contains? (:index/by-type idx) :macro))
    (is (contains? (:index/by-type idx) :protocol))
    (is (contains? (:index/by-type idx) :record))
    (is (contains? (:index/by-type idx) :var))))

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

(deftest build-index-by-type-counts
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= 2 (count (get (:index/by-type idx) :fn))))
    (is (= 1 (count (get (:index/by-type idx) :macro))))
    (is (= 1 (count (get (:index/by-type idx) :protocol))))
    (is (= 2 (count (get (:index/by-type idx) :record))))
    (is (= 2 (count (get (:index/by-type idx) :var))))))

;; ---------------------------------------------------------------------------
;; find-symbol
;; ---------------------------------------------------------------------------

(deftest find-symbol-by-qualified-name
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (seq (sut/find-symbol idx 'fixtures.valid-defs/create-user)))
    (is (= 'fixtures.valid-defs/create-user
           (:sym/name (first (sut/find-symbol idx 'fixtures.valid-defs/create-user)))))))

(deftest find-symbol-by-simple-name
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (seq (sut/find-symbol idx 'create-user)))
    (is (= 1 (count (sut/find-symbol idx 'create-user))))))

(deftest find-symbol-returns-nil-for-missing
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (nil? (sut/find-symbol idx 'nonexistent)))))

(deftest find-symbol-by-string-name
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (seq (sut/find-symbol idx "create-user")))
    (is (seq (sut/find-symbol idx "fixtures.valid-defs/create-user")))))

;; ---------------------------------------------------------------------------
;; find-namespace
;; ---------------------------------------------------------------------------

(deftest find-namespace-works
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (some? (sut/find-namespace idx 'fixtures.valid-defs)))
    (is (= 'fixtures.valid-defs (:ns/name (sut/find-namespace idx 'fixtures.valid-defs))))))

(deftest find-namespace-returns-nil-for-missing
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (nil? (sut/find-namespace idx 'nonexistent.ns)))))

;; ---------------------------------------------------------------------------
;; find-by-type
;; ---------------------------------------------------------------------------

(deftest find-by-type-functions
  (let [idx (parse-and-index "valid_defs.clj")
        fns (sut/find-by-type idx :fn)]
    (is (= 2 (count fns)))
    (is (some #(= 'fixtures.valid-defs/create-user (:sym/name %)) fns))
    (is (some #(= 'fixtures.valid-defs/format-name (:sym/name %)) fns))))

(deftest find-by-type-macros
  (let [idx (parse-and-index "valid_defs.clj")
        macros (sut/find-by-type idx :macro)]
    (is (= 1 (count macros)))
    (is (= 'fixtures.valid-defs/with-tx (:sym/name (first macros))))))

(deftest find-by-type-protocols
  (let [idx (parse-and-index "valid_defs.clj")
        protocols (sut/find-by-type idx :protocol)]
    (is (= 1 (count protocols)))
    (is (= 'fixtures.valid-defs/UserProtocol (:sym/name (first protocols))))))

(deftest find-by-type-records
  (let [idx (parse-and-index "valid_defs.clj")
        records (sut/find-by-type idx :record)]
    (is (= 2 (count records)))))

(deftest find-by-type-vars
  (let [idx (parse-and-index "valid_defs.clj")
        vars (sut/find-by-type idx :var)]
    (is (= 2 (count vars)))))

(deftest find-by-type-empty-for-missing-type
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= [] (sut/find-by-type idx :multimethod)))))

;; ---------------------------------------------------------------------------
;; find-by-protocol
;; ---------------------------------------------------------------------------

(deftest find-by-protocol-no-methods-with-current-metadata
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= [] (sut/find-by-protocol idx 'UserProtocol)))))

;; Protocol methods are currently not tracked in :sym/protocol
;; because defrecord/deftype method forms are inside the record body.

;; ---------------------------------------------------------------------------
;; find-by-record
;; ---------------------------------------------------------------------------

(deftest find-by-record-no-methods-with-current-metadata
  (let [idx (parse-and-index "valid_defs.clj")]
    (is (= [] (sut/find-by-record idx 'User)))))

;; Same as above — defrecord method forms are body-level.

;; ---------------------------------------------------------------------------
;; remove-file!
;; ---------------------------------------------------------------------------

(deftest remove-file-removes-symbols
  (let [idx (parse-and-index "valid_defs.clj")
        file-path (-> idx :index/by-file keys first)
        removed (sut/remove-file! idx file-path)]
    (is (empty? (:index/by-qname removed)))
    (is (empty? (:index/by-simple removed)))
    (is (empty? (:index/by-file removed)))
    (is (every? empty? (vals (:index/by-type removed))))))

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

(deftest metadata-empty-for-dynamic-var
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