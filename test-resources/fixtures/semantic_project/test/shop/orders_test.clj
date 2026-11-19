(ns shop.orders-test
  (:require [clojure.test :refer [deftest is]]
            [shop.orders :as orders]))

(deftest order-total-test
  (is (= 7 (orders/order-total {:items [{:price 2 :quantity 2} {:price 3 :quantity 1}]}))))
