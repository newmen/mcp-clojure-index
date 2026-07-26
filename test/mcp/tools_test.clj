(ns mcp.tools-test
  (:require [clojure.string :as s]
            [clojure.test :refer :all]
            [mcp.tools :as sut]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.config :as config]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-tools"
         :search/top-k 100
         :search/re-rank-top 10))

(defn- make-sym
  [sym-name file-path chunk-id & {:keys [ftype] :or {ftype :fn}}]
  {:sym/name       (symbol (str "test.ns/" (name sym-name)))
   :sym/simple     sym-name
   :sym/ns         'test.ns
   :sym/type       ftype
   :sym/file       file-path
   :sym/line       1
   :sym/arglists   nil
   :sym/doc        nil
   :sym/protocol   (when (= :protocol ftype) (symbol (str "test.ns/" (name sym-name))))
   :sym/record     (when (= :record ftype) (symbol (str "test.ns/" (name sym-name))))
   :sym/tag        nil
   :sym/chunk-id   chunk-id
   :sym/visibility :public
   :sym/aliases    #{}})

(deftest tool-definitions-contain-all-tools
  (let [names (set (map :name sut/tool-definitions))]
    (is (contains? names "semantic_search"))
    (is (contains? names "find_symbol"))
    (is (contains? names "find_namespace"))
    (is (contains? names "find_callers"))
    (is (contains? names "find_callees"))
    (is (contains? names "find_protocol"))
    (is (contains? names "find_record"))
    (is (contains? names "find_macro"))
    (is (= 8 (count names)))))

(deftest tool-definitions-have-required-fields
  (doseq [t sut/tool-definitions]
    (is (contains? t :name))
    (is (contains? t :description))
    (is (contains? t :inputSchema))
    (let [schema (:inputSchema t)]
      (is (= "object" (:type schema))))))

(deftest handle-tools-list-returns-all-tools
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tools-list state)
        tool-names (set (map :name (:tools result)))]
    (is (contains? tool-names "semantic_search"))
    (is (contains? tool-names "find_symbol"))
    (is (contains? tool-names "find_namespace"))
    (is (contains? tool-names "find_callers"))
    (is (contains? tool-names "find_callees"))
    (is (contains? tool-names "find_protocol"))
    (is (contains? tool-names "find_record"))
    (is (contains? tool-names "find_macro"))))

(deftest find-symbol-by-qualified-name
  (let [cid (UUID/randomUUID)
        sym (make-sym 'my-func "/f.clj" cid)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_symbol" {:name "test.ns/my-func"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "test.ns/my-func"))
    (is (s/includes? text "my-func"))))

(deftest find-symbol-by-simple-name
  (let [cid (UUID/randomUUID)
        sym (make-sym 'my-func "/f.clj" cid)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_symbol" {:name "my-func"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "my-func"))))

(deftest find-symbol-returns-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_symbol" {:name "nonexistent"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest find-namespace-found
  (let [ns-chunk {:chunk/type :ns :chunk/name "test.ns" :chunk/source "(ns test.ns)" :chunk/file "/f.clj"}
        index (si/build-index [] [ns-chunk])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_namespace" {:name "test.ns"} state)
        text (:text (first (:content result)))]
    (is (some? text))))

(deftest find-namespace-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_namespace" {:name "missing.ns"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest find-callers-found
  (let [gr (-> (graph/empty-graph)
               (graph/add-file!
                [{:edge/from 'test.ns/caller :edge/from-id (UUID/randomUUID)
                  :edge/to 'test.ns/target :edge/to-id (UUID/randomUUID)
                  :edge/type :call :edge/file "/f.clj" :edge/line 1}]))
        state {:config test-config :index (si/empty-index) :graph-state gr}
        result (sut/handle-tool "find_callers" {:symbol "test.ns/target"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "test.ns/caller"))))

(deftest find-callers-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callers" {:symbol "test.ns/unknown"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callers"))))

(deftest find-callees-found
  (let [gr (-> (graph/empty-graph)
               (graph/add-file!
                [{:edge/from 'test.ns/main :edge/from-id (UUID/randomUUID)
                  :edge/to 'test.ns/util :edge/to-id (UUID/randomUUID)
                  :edge/type :call :edge/file "/f.clj" :edge/line 1}]))
        state {:config test-config :index (si/empty-index) :graph-state gr}
        result (sut/handle-tool "find_callees" {:symbol "test.ns/main"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "test.ns/util"))))

(deftest find-callees-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_callees" {:symbol "test.ns/unknown"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No callees"))))

(deftest find-protocol-found
  (let [cid (UUID/randomUUID)
        sym (make-sym 'MyProtocol "/f.clj" cid :ftype :protocol)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_protocol" {:name "test.ns/MyProtocol"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "MyProtocol"))))

(deftest find-protocol-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_protocol" {:name "Missing"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest find-record-found
  (let [cid (UUID/randomUUID)
        sym (make-sym 'MyRecord "/f.clj" cid :ftype :record)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_record" {:name "test.ns/MyRecord"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "MyRecord"))))

(deftest find-record-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_record" {:name "Missing"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest find-macro-all
  (let [cid-1 (UUID/randomUUID)
        cid-2 (UUID/randomUUID)
        sym-1 (make-sym 'mac1 "/f1.clj" cid-1 :ftype :macro)
        sym-2 (make-sym 'mac2 "/f2.clj" cid-2 :ftype :macro)
        index (-> (si/empty-index) (si/add-file! [sym-1]) (si/add-file! [sym-2]))
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_macro" {} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "mac1"))
    (is (s/includes? text "mac2"))))

(deftest find-macro-no-macros
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_macro" {} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "No macros"))))

(deftest semantic-search-empty-query
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "semantic_search" {:query ""} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "empty"))))

(deftest semantic-search-with-results
  (let [cg1 {:chunk/id (UUID/randomUUID) :chunk/ns "test.ns" :chunk/name "create-user"
             :chunk/file "/f.clj" :chunk/type :fn
             :chunk/source "(defn create-user [x] x)"
             :chunk/start-line 1 :chunk/end-line 1
             :chunk/visibility :public :chunk/language "clojure"
             :chunk/metadata {} :chunk/symbols #{} :chunk/hash "a"}
        state {:config test-config
               :index (si/empty-index)
               :graph-state (graph/empty-graph)}]
    (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                  qdrant/search (fn [_cfg _emb _k] [{:chunk cg1 :score 0.85}])
                  embeddings/rerank (fn [_query candidates _cfg]
                                      (mapv #(assoc % :re-rank (:score %)) candidates))]
      (let [result (sut/handle-tool "semantic_search" {:query "find user creation"} state)
            text (:text (first (:content result)))]
        (is (s/includes? text "create-user"))
        (is (s/includes? text "test.ns"))
        (is (s/includes? text "Result"))))))

(deftest semantic-search-no-results
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}]
    (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (fn [_] [0.1 0.2 0.3]) texts))
                  qdrant/search (fn [_cfg _emb _k] [])
                  embeddings/rerank (fn [_query candidates _cfg] (mapv #(assoc % :re-rank (:score %)) candidates))]
      (let [result (sut/handle-tool "semantic_search" {:query "nothing"} state)
            text (:text (first (:content result)))]
        (is (s/includes? text "No results"))))))