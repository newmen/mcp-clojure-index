(ns mcp.qdrant-test
  "The Qdrant adapter's point format and its behaviour with Qdrant down."
  (:require [clojure.test :refer [deftest is]]
            [mcp.config :as config]
            [mcp.qdrant :as sut]))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-collection"))

(def ^:private unit
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :file "/proj/src/shop/orders.clj"
   :row 5
   :kind "defn"
   :name "shop.orders/order-total"
   :doc "Sum of line items."
   :text "shop.orders/order-total\n[order]"
   :hash "abc"})

(deftest point-payload-carries-the-search-unit-file-mtime-and-format
  (is (= {:id "00000000-0000-0000-0000-000000000001"
          :vector [0.1 0.2]
          :payload {"format" sut/payload-format
                    "file" "/proj/src/shop/orders.clj"
                    "mtime" 42
                    "row" 5
                    "kind" "defn"
                    "name" "shop.orders/order-total"
                    "doc" "Sum of line items."
                    "text" "shop.orders/order-total\n[order]"}}
         (#'sut/unit->point 42 unit [0.1 0.2]))))

(deftest point-from-search-reads-back-as-a-search-unit
  (is (= (-> unit (dissoc :hash) (update :id str))
         (#'sut/point->unit {:id "00000000-0000-0000-0000-000000000001"
                             :payload {:format 2 :file "/proj/src/shop/orders.clj" :mtime 42
                                       :row 5 :kind "defn" :name "shop.orders/order-total"
                                       :doc "Sum of line items."
                                       :text "shop.orders/order-total\n[order]"}}))))

(deftest every-operation-throws-when-qdrant-is-unreachable
  (doseq [operation [#(sut/create-collection! test-config)
                     #(sut/delete-collection! test-config)
                     #(sut/stored-format test-config)
                     #(sut/embedded-files test-config)
                     #(sut/upsert! test-config [unit] [[0.1 0.2]] 42)
                     #(sut/delete! test-config "/proj/src/shop/orders.clj")
                     #(sut/search test-config [0.1 0.2] 10)]]
    (is (thrown? Exception (operation)))))
