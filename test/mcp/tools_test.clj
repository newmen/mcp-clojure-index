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
  (let [names (set (map :name (sut/tool-definitions)))]
    (is (contains? names "semantic_search"))
    (is (contains? names "find_definition"))
    (is (contains? names "find_usages"))
    (is (contains? names "find_protocol"))
    (is (contains? names "find_record"))
    (is (contains? names "find_macro"))
    (is (= 6 (count names)))))

(deftest tool-definitions-have-required-fields
  (doseq [t (sut/tool-definitions)]
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
    (is (contains? tool-names "find_definition"))
    (is (contains? tool-names "find_usages"))
    (is (contains? tool-names "find_protocol"))
    (is (contains? tool-names "find_record"))
    (is (contains? tool-names "find_macro"))))

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

(defn- search-hit
  [hit-name line score]
  {:chunk {:chunk/id (UUID/randomUUID) :chunk/ns "test.ns" :chunk/name hit-name
           :chunk/file (str "/proj/src/test/" hit-name ".clj") :chunk/type :fn
           :chunk/source (str "(defn " hit-name " [x] (secret-body x))")
           :chunk/start-line line :chunk/end-line (inc line)
           :chunk/visibility :public :chunk/language "clojure"
           :chunk/symbols #{} :chunk/hash "deadbeef"}
   :score score})

(defn- semantic-search-text
  "Run semantic_search with Qdrant/Ollama stubbed to return `hits`."
  [cfg index hits args]
  (with-redefs [embeddings/generate (fn [_texts _cfg] [[0.1 0.2 0.3]])
                qdrant/search (fn [_cfg _emb _k] hits)
                embeddings/rerank (fn [_query candidates _cfg]
                                    (mapv #(assoc % :re-rank (:score %)) candidates))]
    (-> (sut/handle-tool "semantic_search" args
                         {:config cfg :index index :graph-state (graph/empty-graph)})
        :content first :text)))

(deftest semantic-search-returns-one-compact-line-per-hit
  (let [create-user (assoc (make-sym 'create-user "/proj/src/test/create_user.clj" (UUID/randomUUID))
                           :sym/doc "Create a user.\nSecond docstring line.")
        index (si/add-file! (si/empty-index) [create-user])
        cfg (assoc test-config :index/root-path "/proj")
        text (semantic-search-text cfg index
                                   [(search-hit "create-user" 3 0.91234)
                                    (search-hit "delete-user" 10 0.5)]
                                   {:query "find user creation"})]
    (is (= (str "0.91  src/test/create-user.clj:3  fn  test.ns/create-user  Create a user.\n"
                "0.50  src/test/delete-user.clj:10  fn  test.ns/delete-user")
           text))))

(deftest semantic-search-never-expands-with-same-file-definitions
  (let [sibling (make-sym 'sibling "/proj/src/test/create-user.clj" (UUID/randomUUID))
        index (si/add-file! (si/empty-index) [sibling])
        cfg (assoc test-config :index/root-path "/proj")
        text (semantic-search-text cfg index [(search-hit "create-user" 3 0.9)]
                                   {:query "users"})]
    (is (= "0.90  src/test/create-user.clj:3  fn  test.ns/create-user" text))))

(deftest semantic-search-limit-pages-and-respects-budget
  (let [cfg (assoc test-config :index/root-path "/proj" :output/limit 2)
        hits (mapv #(search-hit (str "f" %) % (- 1.0 (* % 0.1))) (range 1 5))]
    (is (= (str "0.90  src/test/f1.clj:1  fn  test.ns/f1\n"
                "truncated: shown 1 of 4, pass offset=1 or narrow the query")
           (semantic-search-text cfg (si/empty-index) hits {:query "f" :limit 1})))
    (is (= (str "0.80  src/test/f2.clj:2  fn  test.ns/f2\n"
                "0.70  src/test/f3.clj:3  fn  test.ns/f3\n"
                "truncated: shown 3 of 4, pass offset=3 or narrow the query")
           (semantic-search-text cfg (si/empty-index) hits {:query "f" :limit 99 :offset 1})))))

(deftest semantic-search-schema-accepts-limit-and-offset
  (let [schema (:inputSchema (first (filter (comp #{"semantic_search"} :name) (sut/tool-definitions))))]
    (is (= #{:query :limit :offset} (set (keys (:properties schema)))))))

(deftest semantic-search-no-results
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}]
    (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (fn [_] [0.1 0.2 0.3]) texts))
                  qdrant/search (fn [_cfg _emb _k] [])
                  embeddings/rerank (fn [_query candidates _cfg] (mapv #(assoc % :re-rank (:score %)) candidates))]
      (let [result (sut/handle-tool "semantic_search" {:query "nothing"} state)
            text (:text (first (:content result)))]
        (is (s/includes? text "No results"))))))

;; ---------------------------------------------------------------------------
;; Edge cases: tools with nil/empty arguments
;; ---------------------------------------------------------------------------

(deftest find-protocol-with-nil-name
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_protocol" {:name nil} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest find-record-with-nil-name
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_record" {:name nil} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

(deftest semantic-search-with-nil-query
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "semantic_search" {:query nil} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "empty"))))

(deftest find-macro-with-specific-name
  (let [cid (UUID/randomUUID)
        sym (make-sym 'my-macro "/f.clj" cid :ftype :macro)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_macro" {:name "my-macro"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "my-macro"))))

(deftest find-macro-specific-name-not-found
  (let [state {:config test-config :index (si/empty-index) :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_macro" {:name "nonexistent"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "not found"))))

;; ---------------------------------------------------------------------------
;; Edge cases: tools with fully populated index
;; ---------------------------------------------------------------------------

(deftest find-protocol-with-qualified-name
  (let [cid (UUID/randomUUID)
        sym (make-sym 'MyProtocol "/f.clj" cid :ftype :protocol)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_protocol" {:name "test.ns/MyProtocol"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "MyProtocol"))))

(deftest find-record-with-qualified-name
  (let [cid (UUID/randomUUID)
        sym (make-sym 'MyRecord "/f.clj" cid :ftype :record)
        index (si/add-file! (si/empty-index) [sym])
        state {:config test-config :index index :graph-state (graph/empty-graph)}
        result (sut/handle-tool "find_record" {:name "test.ns/MyRecord"} state)
        text (:text (first (:content result)))]
    (is (s/includes? text "MyRecord"))))