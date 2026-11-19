(ns mcp.search-units-test
  "Search units built from the precise index of a fixture project."
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.search-units :as sut]
            [mcp.test-project :as project])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private fixture-root
  (.getCanonicalPath (File. "test-resources/fixtures/semantic_project")))

(def ^:private fixture-index
  (delay (code-index/build (assoc project/config :index/root-path fixture-root))))

(defn- relative
  [file-path]
  (subs file-path (inc (count fixture-root))))

(defn- located
  [units]
  (set (map (juxt (comp relative :filename) :row :kind :name) units)))

(deftest search-units-cover-every-definition-and-top-level-edn-map-entry
  (is (= #{["src/shop/orders.clj" 1 "ns" "shop.orders"]
           ["src/shop/orders.clj" 5 "defn" "shop.orders/order-total"]
           ["src/shop/orders.clj" 10 "defn" "shop.orders/apply-discount"]
           ["src/shop/orders.clj" 15 "defmulti" "shop.orders/shipping-cost"]
           ["src/shop/orders.clj" 19 "defmethod" "shop.orders/shipping-cost :courier"]
           ["src/shop/orders.clj" 23 "s/def" ":shop.orders/email"]
           ["src/shop/orders.clj" 26 "defn" "shop.orders/send-invoice-email"]
           ["src/shop/orders.clj" 31 "defrecord" "shop.orders/Customer"]
           ["src/shop/inventory.clj" 1 "ns" "shop.inventory"]
           ["src/shop/inventory.clj" 4 "defn" "shop.inventory/reserve-stock"]
           ["src/shop/inventory.clj" 9 "defn" "shop.inventory/restock-product"]
           ["test/shop/orders_test.clj" 1 "ns" "shop.orders-test"]
           ["test/shop/orders_test.clj" 5 "deftest" "shop.orders-test/order-total-test"]
           ["resources/config.edn" 1 "edn" ":db/connection-pool"]
           ["resources/config.edn" 3 "edn" ":smtp/server"]}
         (located (sut/search-units @fixture-index)))))

(defn- unit-named
  [unit-name]
  (first (filter (comp #{unit-name} :name) (sut/search-units @fixture-index))))

(deftest embedding-text-is-name-arglists-docstring-and-source
  (is (= (str "shop.inventory/reserve-stock\n"
              "[stock product-id quantity]\n"
              "Decrease the available stock of a product when an order is placed.\n"
              "(defn reserve-stock\n"
              "  \"Decrease the available stock of a product when an order is placed.\"\n"
              "  [stock product-id quantity]\n"
              "  (update stock product-id - quantity))")
         (:text (unit-named "shop.inventory/reserve-stock")))))

(deftest edn-entry-embedding-text-is-key-and-entry-source
  (is (= (str ":db/connection-pool\n"
              ":db/connection-pool {:max-connections 10\n"
              "                      :timeout-ms 3000}")
         (:text (unit-named ":db/connection-pool")))))

(deftest search-unit-carries-the-docstring
  (is (= "Sum of price times quantity over all line items of an order."
         (:doc (unit-named "shop.orders/order-total")))))

(deftest embedding-text-source-is-truncated-to-about-2000-characters
  (let [unit (sut/definition-unit {"/f.clj" (vec (repeat 400 "(+ 1 2 3 4 5 6 7 8 9)"))}
               {:ns 'big :name 'huge :kind "def" :filename "/f.clj"
                :row 1 :col 1 :end-row 400 :end-col 23})]
    (testing "the source part is cut, the name stays"
      (is (s/starts-with? (:text unit) "big/huge\n"))
      (is (<= 2000 (count (:text unit)) 2100)))))

(deftest search-units-of-the-same-content-have-the-same-id-and-hash
  (let [units (sut/search-units @fixture-index)]
    (is (= (map (juxt :id :hash) units)
           (map (juxt :id :hash) (sut/search-units @fixture-index))))
    (is (= (count units) (count (set (map :id units)))))))
