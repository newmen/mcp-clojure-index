(ns mcp.tools.semantic-search-test
  "`semantic_search` on Search units, with Qdrant and Ollama replaced by
   stand-ins or unreachable."
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.semantic-index :as semantic-index]
            [mcp.tool :as tool]
            [mcp.tools.semantic-search]
            [mcp.test-project :as project])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private fixture-root
  (.getCanonicalPath (File. "test-resources/fixtures/semantic_project")))

(def ^:private fixture-index
  (delay (code-index/build (assoc project/config :index/root-path fixture-root))))

(def ^:private config
  (assoc project/config
         :index/root-path fixture-root
         :qdrant/port 19999
         :ollama/url "http://localhost:19999/v1"))

(defn- fresh-semantic-index
  "Creates a SemanticIndex in which every file of the fixture is embedded.

  () -> IO SemanticIndex"
  []
  (let [semantic-index (semantic-index/create config)]
    (swap! (:state semantic-index) assoc
           :prepared? true
           :embedded (code-index/file-mtimes @fixture-index))
    semantic-index))

(defn- hit
  "A ScoredUnit as Qdrant returns it, for a unit of the fixture.

  String -> Long -> String -> String -> Maybe String -> Double -> IO ScoredUnit"
  [relative-path row kind unit-name doc score]
  {:unit (cond-> {:id (str (random-uuid))
                  :filename (str fixture-root "/" relative-path)
                  :row row :kind kind :name unit-name
                  :text (str unit-name "\n(source)")}
           doc (assoc :doc doc))
   :score score})

(def ^:private hits
  [(hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total"
        "Sum of price times quantity over all line items of an order." 0.91234)
   (hit "resources/config.edn" 1 "edn" ":db/connection-pool" nil 0.5)])

(defn- current-collection
  "A stand-in for `qdrant/verify-layout!` for a collection of the current
   CollectionLayout.

  Config -> Boolean -> IO ()"
  [_cfg _holds-points?]
  nil)

(defn- answer-searching
  "The answer text of `semantic_search` on the fixture, with the check of
   the collection replaced by `verify-layout` and Qdrant search by
   `qdrant-search`, stubbed Ollama and a reranker that keeps the vector
   scores.

  ToolArgs -> SemanticIndex -> (Config -> Boolean -> IO ()) -> (Config -> Embedding -> Long -> #{String} -> IO [ScoredUnit]) -> IO String"
  [args semantic-index verify-layout qdrant-search]
  (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2]) texts))
                qdrant/verify-layout! verify-layout
                qdrant/search qdrant-search
                embeddings/rerank (fn [_query candidates _cfg]
                                    (mapv #(assoc % :re-rank (:score %)) candidates))]
    (-> (tool/call :semantic_search args {:config config
                                          :code-index @fixture-index
                                          :semantic-index semantic-index})
        :content first :text)))

(defn- answer
  "The answer text of `semantic_search` on the fixture, with Qdrant finding
   `stub-hits`, stubbed Ollama and a reranker that keeps the vector scores.

  ToolArgs -> IO String
  ToolArgs -> SemanticIndex -> [ScoredUnit] -> IO String"
  ([args] (answer args (fresh-semantic-index) hits))
  ([args semantic-index stub-hits]
   (answer-searching args semantic-index current-collection
                     (fn [_cfg _vector top-k excluded-files]
                       (->> stub-hits
                            (remove (comp (set excluded-files) :filename :unit))
                            (take top-k))))))

(deftest answers-one-compact-line-per-search-unit
  (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity over all line items of an order.\n"
              "0.50  resources/config.edn:1  edn  :db/connection-pool")
         (answer {:query "qwerty"}))))

(deftest hit-lines-carry-no-type-signature
  (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity. (+15 chars)")
         (answer {:query "qwerty"} (fresh-semantic-index)
                 [(hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total"
                       "Sum of price times quantity.\n  Over all items.\n\n  Order -> Double" 0.91234)]))))

(deftest without-reranker-no-raw-vector-score-is-shown
  (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2]) texts))
                qdrant/verify-layout! current-collection
                qdrant/search (fn [_cfg _vector _top-k _excluded-files] hits)
                embeddings/rerank (fn [_query candidates _cfg] candidates)]
    (is (= (str "src/shop/orders.clj:5  defn  shop.orders/order-total  "
                "Sum of price times quantity over all line items of an order.\n"
                "resources/config.edn:1  edn  :db/connection-pool")
           (-> (tool/call :semantic_search {:query "qwerty"}
                          {:config config
                           :code-index @fixture-index
                           :semantic-index (fresh-semantic-index)})
               :content first :text)))))

(deftest weak-best-relevance-is-announced-before-the-hits
  (is (= (str "no confident matches (best 0.04), try rephrasing\n"
              "0.04  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity over all line items of an order.\n"
              "0.01  resources/config.edn:1  edn  :db/connection-pool")
         (answer {:query "qwerty"} (fresh-semantic-index)
                 [(hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total"
                       "Sum of price times quantity over all line items of an order." 0.04)
                  (hit "resources/config.edn" 1 "edn" ":db/connection-pool" nil 0.01)])))
  (testing "a best relevance at the threshold is confident"
    (is (st/starts-with? (answer {:query "qwerty"} (fresh-semantic-index)
                                 [(hit "resources/config.edn" 1 "edn" ":db/connection-pool" nil
                                       (:search/min-relevance config))])
                         "0.30  resources/config.edn:1"))))

(deftest a-best-hit-under-0-3-is-not-confident
  (is (= (str "no confident matches (best 0.15), try rephrasing\n"
              "0.15  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity over all line items of an order.")
         (answer {:query "qwerty"} (fresh-semantic-index)
                 [(hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total"
                       "Sum of price times quantity over all line items of an order." 0.15)])))
  (is (= (str "0.95  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity over all line items of an order.")
         (answer {:query "qwerty"} (fresh-semantic-index)
                 [(hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total"
                       "Sum of price times quantity over all line items of an order." 0.95)]))))

(deftest without-reranker-there-is-no-confidence-line
  (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2]) texts))
                qdrant/verify-layout! current-collection
                qdrant/search (fn [_cfg _vector _top-k _excluded-files]
                                [(hit "resources/config.edn" 1 "edn" ":db/connection-pool" nil 0.01)])
                embeddings/rerank (fn [_query candidates _cfg] candidates)]
    (is (= "resources/config.edn:1  edn  :db/connection-pool"
           (-> (tool/call :semantic_search {:query "qwerty"}
                          {:config config
                           :code-index @fixture-index
                           :semantic-index (fresh-semantic-index)})
               :content first :text)))))

(deftest a-definition-matched-by-its-words-is-found-without-the-vector-search
  (is (= (str "src/shop/orders.clj:10  defn  shop.orders/apply-discount  "
              "Reduce an amount by a percentage discount code.\n"
              "src/shop/orders.clj:1  ns  shop.orders  Orders of the shop: totals, discounts and shipping.")
         (with-redefs [embeddings/rerank (fn [_query candidates _cfg] candidates)]
           (answer {:query "percentage discount"} (fresh-semantic-index) [])))))

(deftest limit-and-offset-page-within-the-output-budget
  (let [many (mapv #(hit "src/shop/orders.clj" % "defn" (str "shop.orders/f" %) nil (- 1.0 (* % 0.01)))
                   (range 1 81))]
    (testing "limit lowers the page size; the reranker keeps :search/re-rank-top hits"
      (is (= (str "0.99  src/shop/orders.clj:1  defn  shop.orders/f1\n"
                  "truncated: shown 1 of 10, pass offset=1 or narrow the query")
             (answer {:query "f" :limit 1} (fresh-semantic-index) many))))
    (testing "offset returns the next page"
      (is (st/starts-with? (answer {:query "f" :offset 1 :limit 1} (fresh-semantic-index) many)
                           "0.98  src/shop/orders.clj:2  defn  shop.orders/f2\n")))
    (testing "the answer never exceeds :output/max-chars"
      (is (<= (count (answer {:query "f"} (fresh-semantic-index) many))
              (:output/max-chars config))))))

(deftest stale-embeddings-are-reported-with-the-count-of-files
  (let [semantic-index (fresh-semantic-index)
        inventory (str fixture-root "/src/shop/inventory.clj")
        orders (str fixture-root "/src/shop/orders.clj")]
    (swap! (:state semantic-index) update :embedded dissoc inventory orders)
    (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
                "Sum of price times quantity over all line items of an order.\n"
                "0.50  resources/config.edn:1  edn  :db/connection-pool\n"
                "embeddings stale for 2 files")
           (answer {:query "qwerty"} semantic-index hits)))))

(deftest stale-note-fits-in-the-output-budget
  (let [many (mapv #(hit "src/shop/orders.clj" % "defn" (str "shop.orders/f" %) nil 0.5)
                   (range 1 81))
        text (answer {:query "f"} (semantic-index/create config) many)]
    (is (st/ends-with? text "\nembeddings stale for 6 files"))
    (is (<= (count text) (:output/max-chars config)))))

(deftest no-hits-says-so
  (is (= "no results for: nothing (checked 5 files, excluding tests)"
         (answer {:query "nothing"} (fresh-semantic-index) [])))
  (is (= "no results for: nothing (checked 6 files, including tests)"
         (answer {:query "nothing" :include_tests true} (fresh-semantic-index) []))))

;; Acceptance case 11
(deftest excluding-tests-keeps-the-recall-of-the-vector-search
  (let [test-hits (mapv #(hit "test/shop/orders_test.clj" % "deftest" (str "shop.orders-test/t" %) nil 0.99)
                        (range 1 4))
        orders-hit (hit "src/shop/orders.clj" 5 "defn" "shop.orders/order-total" nil 0.9)]
    (is (= "0.90  src/shop/orders.clj:5  defn  shop.orders/order-total"
           (with-redefs [config (assoc config :search/top-k 2)]
             (answer {:query "qwerty"} (fresh-semantic-index) (conj test-hits orders-hit)))))))

(deftest test-definitions-are-found-only-with-include-tests
  (let [test-hit (hit "test/shop/orders_test.clj" 5 "deftest" "shop.orders-test/order-total-test" nil 0.95)
        stub-hits (conj hits test-hit)
        test-line "0.95  test/shop/orders_test.clj:5  deftest  shop.orders-test/order-total-test"]
    (testing "by default Search units in Test paths are dropped, from the vector and the word matching"
      (let [lines (st/split-lines (answer {:query "order total test"} (fresh-semantic-index) stub-hits))]
        (is (some #(st/starts-with? % "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total") lines))
        (is (not-any? #(st/includes? % "test/shop/orders_test.clj") lines))))
    (testing "with include_tests they are hits"
      (is (some #{test-line}
                (st/split-lines (answer {:query "order total test" :include_tests true}
                                        (fresh-semantic-index) stub-hits)))))))

;; ---------------------------------------------------------------------------
;; Search exclusions
;; ---------------------------------------------------------------------------

(def ^:private search-excluding-config
  (assoc config :search/exclude (config/compile-exclude-patterns ["src/shop/inventory" "resources/config.edn"])))

(defn- inventory-hit
  "A ScoredUnit of `shop.inventory/reserve-stock`, a file under a Search
   exclusion.

  () -> ScoredUnit"
  []
  (hit "src/shop/inventory.clj" 4 "defn" "shop.inventory/reserve-stock"
       "Decrease the available stock of a product when an order is placed." 0.97))

(deftest files-under-a-search-exclusion-are-never-hits
  (testing (str "neither from the vector search nor from the word matching, "
                "while their points wait for the sync to delete them")
    (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
                "Sum of price times quantity over all line items of an order.\n"
                "embeddings stale for 2 files")
           (with-redefs [config search-excluding-config]
             (answer {:query "decrease the available stock of a product"} (fresh-semantic-index)
                     (conj hits (inventory-hit)))))))
  (testing "without the exclusion the same files are hits"
    (is (st/includes? (answer {:query "decrease the available stock of a product"} (fresh-semantic-index)
                              (conj hits (inventory-hit)))
                      "0.97  src/shop/inventory.clj:4  defn  shop.inventory/reserve-stock"))))

(deftest files-under-a-search-exclusion-are-neither-checked-nor-stale-once-synced
  (let [semantic-index (fresh-semantic-index)]
    (swap! (:state semantic-index) update :embedded dissoc
           (str fixture-root "/src/shop/inventory.clj") (str fixture-root "/resources/config.edn"))
    (with-redefs [config search-excluding-config]
      (is (= "no results for: nothing (checked 3 files, excluding tests)"
             (answer {:query "nothing"} semantic-index []))))))

(deftest blank-query-is-rejected
  (is (= "query is empty" (answer {:query "  "})))
  (is (= "query is empty" (answer {}))))

(deftest unavailable-qdrant-or-ollama-is-reported
  (let [text (-> (tool/call :semantic_search {:query "order total"}
                            {:config config
                             :code-index @fixture-index
                             :semantic-index (fresh-semantic-index)})
                 :content first :text)]
    (is (st/starts-with? text "semantic search unavailable (Qdrant or Ollama not reachable"))
    (is (st/includes? text "find_definition"))))

;; ---------------------------------------------------------------------------
;; Acceptance case 9: real Qdrant and Ollama
;; ---------------------------------------------------------------------------

(def ^:private expected-top-3
  {"sum the price of all items in an order" "shop.orders/order-total"
   "reduce an amount by a percentage discount" "shop.orders/apply-discount"
   "decrease stock when an order is placed" "shop.inventory/reserve-stock"
   "put delivered units back into the warehouse" "shop.inventory/restock-product"
   "email the invoice to the customer" "shop.orders/send-invoice-email"
   "database connection pool settings" ":db/connection-pool"
   "shipping price for a courier delivery" "shop.orders/shipping-cost"
   "spec validating an email address" ":shop.orders/email"})

(defn- top-names
  "The names of the first three hits of an answer.

  String -> [Maybe String]"
  [text]
  (->> (st/split-lines text)
       (take 3)
       ;; `[score]  path:line  kind  ns/name ...`: the score is absent without the reranker
       (map #(nth (drop-while (partial re-matches #"\d+\.\d+") (st/split % #"  ")) 2 nil))))

(defn- temp-cache-dir
  "Creates a fresh temporary embedding cache directory and returns its path.

  () -> IO String"
  []
  (str (Files/createTempDirectory "semantic-search-it" (make-array FileAttribute 0))))

(defn- answers
  "The answer of `semantic_search` to each of `queries` on the project at
   `root`; also asserts that every file is embedded and every answer fits
   the budget. Uses a fresh collection and embedding cache, so it
   measures the models of `cfg`.

  Config -> String -> [String] -> IO {String String}"
  [cfg root queries]
  (let [cfg (assoc cfg
                   :index/root-path root
                   :qdrant/collection (str "mcp-integration-" (random-uuid))
                   :embedding/cache-dir (temp-cache-dir))
        index (code-index/build cfg)
        semantic-index (semantic-index/create cfg)]
    (try
      (semantic-index/sync! semantic-index index)
      (is (= [] (semantic-index/stale-files semantic-index index)) "every file embedded")
      (into (sorted-map)
            (map (fn [query]
                   [query (project/answer-text
                           cfg (tool/call :semantic_search {:query query}
                                          {:config cfg :code-index index :semantic-index semantic-index}))]))
            queries)
      (finally
        (qdrant/delete-collection! cfg)))))

(defn- top-3-misses
  "The queries of `expected` whose expected Definition is not among the
   first three hits of `semantic_search` on the project at `root`, each
   with its answer.

  Config -> String -> {String String} -> IO {String String}"
  [cfg root expected]
  (into (sorted-map)
        (keep (fn [[query text]]
                (let [expected-name (expected query)]
                  (when-not (some #(and % (st/starts-with? % expected-name)) (top-names text))
                    [query (str expected-name "\n" text)]))))
        (answers cfg root (keys expected))))

(deftest ^:integration expected-definition-is-in-the-top-3-of-semantic-search
  (is (= {} (top-3-misses project/config fixture-root expected-top-3))))

;; ---------------------------------------------------------------------------
;; Acceptance case 12: Russian docstrings, queries in English and Russian.
;; Needs the reranker; fails with the code-specific models (ADR-0008).
;; ---------------------------------------------------------------------------

(def ^:private multilingual-root
  (.getCanonicalPath (File. "test-resources/fixtures/multilingual_project")))

(def ^:private multilingual-top-3
  "Queries in English and Russian against the Russian docstrings of the
   multilingual fixture, each with the Definition expected in the top 3.

  {String String}"
  {"how much a reader pays for returning a book late" "library.loans/overdue-fine"
   "lend a book to a reader for two weeks" "library.loans/issue-book"
   "remind the reader by email that the due date is near" "library.readers/notify-reader"
   "the most borrowed books of a period" "library.catalog/popular-titles"
   "search the catalogue by writer" "library.catalog/find-by-author"
   "a reader pays off a debt" "library.billing/pay-fine"
   "продлить срок, если книгу никто не ждёт" "library.loans/extend-loan"
   "принять возвращённую книгу" "library.loans/return-book"
   "запретить выдачу должникам" "library.readers/block-reader"
   "оформить читательский билет новому посетителю" "library.readers/register-reader"
   "добавить новый экземпляр в фонд" "library.catalog/add-copy"
   "какие экземпляры сейчас можно взять" "library.catalog/available-copies"})

(deftest ^:integration russian-docstrings-are-found-by-english-and-russian-queries
  (is (= {} (top-3-misses project/config multilingual-root multilingual-top-3))))

(deftest ^:integration a-spec-is-found-by-the-meaning-of-the-comment-above-it
  (is (= {} (top-3-misses project/config multilingual-root
                          {"формат номера билета читателя: буква отделения и цифры"
                           ":library.readers/card-number"}))))

;; ---------------------------------------------------------------------------
;; Acceptance case 13: `no confident matches` (ADR-0008).
;; ---------------------------------------------------------------------------

(def ^:private unmatched-queries
  "Queries in English and Russian that no Search unit of either fixture
   matches.

  [String]"
  ["parse a JSON web token and verify its signature"
   "render a 3D scene with shaders on the GPU"
   "convert temperature from Celsius to Fahrenheit"
   "send a push notification to a mobile phone"
   "отрендерить трёхмерную сцену на видеокарте"
   "прогноз погоды на завтра"
   "рассчитать орбиту спутника"])

(defn- confidence-lines
  "The first line of each answer, keyed by query.

  {String String} -> {String String}"
  [query-answers]
  (update-vals query-answers (comp first st/split-lines)))

(deftest ^:integration queries-without-a-matching-unit-get-no-confident-matches
  (doseq [root [multilingual-root fixture-root]]
    (testing root
      (is (= {}
             (into {} (remove (comp #(st/starts-with? % "no confident matches (best ") val))
                   (confidence-lines (answers project/config root unmatched-queries)))))))
  (testing "the queries of case 12 are confident at the 0.05 measured in ADR-0008"
    (is (= {}
           (into {} (filter (comp #(st/starts-with? % "no confident matches") val))
                 (confidence-lines (answers (assoc project/config :search/min-relevance 0.05)
                                            multilingual-root (keys multilingual-top-3))))))))

(deftest a-deleted-emptied-or-replaced-collection-makes-every-file-stale
  (let [lost (ex-info "Qdrant collection was deleted, emptied or replaced" {:qdrant/lost? true})
        no-hits (fn [_cfg _vector _top-k _excluded-files] [])]
    (doseq [[case-name verify-layout qdrant-search]
            [["deleted while searching" current-collection
              (fn [_cfg _vector _top-k _excluded-files] (throw (ex-info "Qdrant search failed" {:qdrant/status 404})))]
             ["emptied or replaced" (fn [_cfg _holds-points?] (throw lost)) no-hits]]]
      (testing case-name
        (let [semantic-index (fresh-semantic-index)]
          (is (= (str "semantic search unavailable: the Qdrant collection was deleted, emptied or replaced, "
                      "every file will be embedded again; "
                      "the precise tools (find_definition, get_source, ...) still work\n"
                      "embeddings stale for 6 files")
                 (answer-searching {:query "qwerty"} semantic-index verify-layout qdrant-search)))
          (is (= 6 (count (semantic-index/stale-files semantic-index @fixture-index)))))))))

(deftest the-collection-is-checked-only-once-it-is-prepared
  (let [checked (atom [])
        semantic-index (fresh-semantic-index)
        unprepared (semantic-index/create config)]
    (doseq [index [semantic-index unprepared]]
      (answer-searching {:query "qwerty"} index
                        (fn [_cfg holds-points?] (swap! checked conj holds-points?))
                        (fn [_cfg _vector _top-k _excluded-files] [])))
    (is (= [true] @checked))))
