(ns mcp.tools.semantic-search-test
  "`semantic_search` on Search units, with Qdrant and Ollama replaced by
   stand-ins or unreachable."
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
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

(defn- fresh-semantic-state
  "Semantic index state in which every file of the fixture is embedded."
  []
  (let [state (semantic-index/make-state)]
    (swap! state assoc
           :prepared? true
           :embedded (code-index/file-mtimes @fixture-index))
    state))

(defn- hit
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

(defn- answer
  ([args] (answer args (fresh-semantic-state) hits))
  ([args semantic-state stub-hits]
   (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2]) texts))
                 qdrant/search (fn [_cfg _vector _top-k] stub-hits)
                 embeddings/rerank (fn [_query candidates _cfg]
                                     (mapv #(assoc % :re-rank (:score %)) candidates))]
     (-> (tool/call :semantic_search args {:config config
                                           :code-index @fixture-index
                                           :semantic-index semantic-state})
         :content first :text))))

(deftest answers-one-compact-line-per-search-unit
  (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
              "Sum of price times quantity over all line items of an order.\n"
              "0.50  resources/config.edn:1  edn  :db/connection-pool")
         (answer {:query "qwerty"}))))

(deftest without-reranker-no-raw-vector-score-is-shown
  (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2]) texts))
                qdrant/search (fn [_cfg _vector _top-k] hits)
                embeddings/rerank (fn [_query candidates _cfg] candidates)]
    (is (= (str "src/shop/orders.clj:5  defn  shop.orders/order-total  "
                "Sum of price times quantity over all line items of an order.\n"
                "resources/config.edn:1  edn  :db/connection-pool")
           (-> (tool/call :semantic_search {:query "qwerty"}
                          {:config config
                           :code-index @fixture-index
                           :semantic-index (fresh-semantic-state)})
               :content first :text)))))

(deftest a-definition-matched-by-its-words-is-found-without-the-vector-search
  (is (= (str "src/shop/orders.clj:10  defn  shop.orders/apply-discount  "
              "Reduce an amount by a percentage discount code.\n"
              "src/shop/orders.clj:1  ns  shop.orders  Orders of the shop: totals, discounts and shipping.")
         (with-redefs [embeddings/rerank (fn [_query candidates _cfg] candidates)]
           (answer {:query "percentage discount"} (fresh-semantic-state) [])))))

(deftest limit-and-offset-page-within-the-output-budget
  (let [many (mapv #(hit "src/shop/orders.clj" % "defn" (str "shop.orders/f" %) nil (- 1.0 (* % 0.01)))
                   (range 1 81))]
    (testing "limit lowers the page size; the reranker keeps :search/re-rank-top hits"
      (is (= (str "0.99  src/shop/orders.clj:1  defn  shop.orders/f1\n"
                  "truncated: shown 1 of 10, pass offset=1 or narrow the query")
             (answer {:query "f" :limit 1} (fresh-semantic-state) many))))
    (testing "offset returns the next page"
      (is (s/starts-with? (answer {:query "f" :offset 1 :limit 1} (fresh-semantic-state) many)
                          "0.98  src/shop/orders.clj:2  defn  shop.orders/f2\n")))
    (testing "the answer never exceeds :output/max-chars"
      (is (<= (count (answer {:query "f"} (fresh-semantic-state) many))
              (:output/max-chars config))))))

(deftest stale-embeddings-are-reported-with-the-count-of-files
  (let [state (fresh-semantic-state)
        inventory (str fixture-root "/src/shop/inventory.clj")
        orders (str fixture-root "/src/shop/orders.clj")]
    (swap! state update :embedded dissoc inventory orders)
    (is (= (str "0.91  src/shop/orders.clj:5  defn  shop.orders/order-total  "
                "Sum of price times quantity over all line items of an order.\n"
                "0.50  resources/config.edn:1  edn  :db/connection-pool\n"
                "embeddings stale for 2 files")
           (answer {:query "qwerty"} state hits)))))

(deftest stale-note-fits-in-the-output-budget
  (let [many (mapv #(hit "src/shop/orders.clj" % "defn" (str "shop.orders/f" %) nil 0.5)
                   (range 1 81))
        text (answer {:query "f"} (semantic-index/make-state) many)]
    (is (s/ends-with? text "\nembeddings stale for 6 files"))
    (is (<= (count text) (:output/max-chars config)))))

(deftest no-hits-says-so
  (is (= "no results for: nothing (checked 6 files, including tests)"
         (answer {:query "nothing"} (fresh-semantic-state) []))))

(deftest blank-query-is-rejected
  (is (= "query is empty" (answer {:query "  "})))
  (is (= "query is empty" (answer {}))))

(deftest unavailable-qdrant-or-ollama-is-reported
  (let [text (-> (tool/call :semantic_search {:query "order total"}
                            {:config config
                             :code-index @fixture-index
                             :semantic-index (fresh-semantic-state)})
                 :content first :text)]
    (is (s/starts-with? text "semantic search unavailable (Qdrant or Ollama not reachable"))
    (is (s/includes? text "find_definition"))))

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
  [text]
  (->> (s/split-lines text)
       (take 3)
       ;; `[score]  path:line  kind  ns/name ...`: the score is absent without the reranker
       (map #(nth (drop-while (partial re-matches #"\d+\.\d+") (s/split % #"  ")) 2 nil))))

(defn- temp-cache-dir
  []
  (str (Files/createTempDirectory "semantic-search-it" (make-array FileAttribute 0))))

(deftest ^:integration expected-definition-is-in-the-top-3-of-semantic-search
  (let [cfg (assoc project/config
                   :index/root-path fixture-root
                   :qdrant/collection (str "mcp-integration-" (random-uuid))
                   :embedding/cache-dir (temp-cache-dir))
        state (semantic-index/make-state)]
    (try
      (semantic-index/sync! cfg state @fixture-index)
      (is (= [] (semantic-index/stale-files state @fixture-index)) "every file embedded")
      (doseq [[query expected] expected-top-3]
        (let [text (-> (tool/call :semantic_search {:query query}
                                  {:config cfg :code-index @fixture-index :semantic-index state})
                       :content first :text)]
          (is (some #(and % (s/starts-with? % expected)) (top-names text))
              (str query " => " expected "\n" text))
          (is (<= (count text) (:output/max-chars cfg)))))
      (finally
        (qdrant/delete-collection! cfg)))))
