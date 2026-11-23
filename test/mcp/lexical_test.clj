(ns mcp.lexical-test
  (:require [clojure.test :refer [deftest is]]
            [mcp.lexical :as sut]))

(set! *warn-on-reflection* true)

(defn- unit
  "A Search unit with only an id, a name and an optional docstring.

  String -> SearchUnit
  String -> Maybe String -> SearchUnit"
  ([unit-name] (unit unit-name nil))
  ([unit-name doc]
   (cond-> {:id unit-name :name unit-name}
     doc (assoc :doc doc))))

(def ^:private units
  [(unit "shop.orders/order-total" "Sum of price times quantity over all line items of an order.")
   (unit "shop.orders/apply-discount" "Reduce an amount by a percentage discount code.")
   (unit "shop.inventory/restock-product" "Add delivered units of a product back to the warehouse stock.")
   (unit ":db/connection-pool")
   (unit "shop.orders/Customer")
   (unit "shop.orders/cancel" "Cancel an order.\n  Refunds the payment.")])

(defn- ranked-names
  "The names of the test units that `rank` returns for `query`, in order.

  String -> [String]"
  [query]
  (mapv :name (sut/rank query units)))

(deftest words-of-the-qualified-name-match
  (is (= ["shop.orders/order-total"] (ranked-names "total"))))

(deftest words-of-the-docstring-match
  (is (= ["shop.orders/apply-discount"] (ranked-names "percentage discount"))))

(deftest every-line-of-the-docstring-matches
  (is (= ["shop.orders/cancel"] (ranked-names "refund"))))

(deftest edn-keys-and-record-names-are-split-into-words
  (is (= [":db/connection-pool"] (ranked-names "database connection pool")))
  (is (= ["shop.orders/Customer"] (ranked-names "customer"))))

(deftest units-matching-more-words-rank-higher
  ;; order-total matches `price` and `quantity`, apply-discount only `code`
  (is (= ["shop.orders/order-total" "shop.orders/apply-discount"]
         (ranked-names "price quantity code"))))

(deftest rare-words-weigh-more-than-common-ones
  ;; `shop` is in three names, `warehouse` in one docstring
  (is (= "shop.inventory/restock-product"
         (first (ranked-names "shop warehouse")))))

(deftest plural-and-past-tense-match-the-base-word
  (is (= ["shop.inventory/restock-product"] (ranked-names "deliver unit")))
  (is (= "shop.orders/order-total" (first (ranked-names "orders with many items")))))

(deftest stop-words-alone-match-nothing
  (is (= [] (ranked-names "all of the an by")))
  (is (= [] (ranked-names ""))))
