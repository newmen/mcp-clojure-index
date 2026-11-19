(ns shop.orders
  "Orders of the shop: totals, discounts and shipping."
  (:require [clojure.spec.alpha :as s]))

(defn order-total
  "Sum of price times quantity over all line items of an order."
  [order]
  (reduce + (map #(* (:price %) (:quantity %)) (:items order))))

(defn apply-discount
  "Reduce an amount by a percentage discount code."
  [amount percent]
  (- amount (* amount (/ percent 100))))

(defmulti shipping-cost
  "Shipping cost of an order, by carrier."
  :carrier)

(defmethod shipping-cost :courier
  [order]
  (* 2 (count (:items order))))

(s/def ::email
  (s/and string? #(re-find #"@" %)))

(defn send-invoice-email
  "Send the invoice of an order to the customer's email address."
  [order]
  (println "mail to" (:email order)))

(defrecord Customer [name email])
