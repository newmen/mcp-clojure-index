(ns mcp.qdrant-test
  "The Qdrant adapter's point format, its search request and its behaviour
   with Qdrant down."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [mcp.config :as config]
            [mcp.qdrant :as sut])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(set! *warn-on-reflection* true)

(def test-config
  (assoc config/defaults
         :qdrant/host "localhost"
         :qdrant/port 19999
         :qdrant/collection "mcp-test-collection"))

(def ^:private unit
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :filename "/proj/src/shop/orders.clj"
   :row 5
   :kind "defn"
   :name "shop.orders/order-total"
   :doc "Sum of line items."
   :text "shop.orders/order-total\n[order]"
   :hash "abc"})

(deftest point-payload-carries-the-search-unit-file-mtime-format-and-model
  (is (= {:id "00000000-0000-0000-0000-000000000001"
          :vector [0.1 0.2]
          :payload {"format" sut/payload-format
                    "model" "bge-m3"
                    "filename" "/proj/src/shop/orders.clj"
                    "mtime" 42
                    "row" 5
                    "kind" "defn"
                    "name" "shop.orders/order-total"
                    "doc" "Sum of line items."
                    "text" "shop.orders/order-total\n[order]"}}
         (#'sut/unit->point "bge-m3" 42 unit [0.1 0.2]))))

(deftest point-from-search-reads-back-as-a-search-unit
  (is (= (-> unit (dissoc :hash) (update :id str))
         (#'sut/point->unit {:id "00000000-0000-0000-0000-000000000001"
                             :payload {:format 3 :filename "/proj/src/shop/orders.clj" :mtime 42
                                       :row 5 :kind "defn" :name "shop.orders/order-total"
                                       :doc "Sum of line items."
                                       :text "shop.orders/order-total\n[order]"}}))))

(deftest every-operation-throws-when-qdrant-is-unreachable
  (doseq [operation [#(sut/create-collection! test-config 768)
                     #(sut/delete-collection! test-config)
                     #(sut/stored-layout test-config)
                     #(sut/embedded-files test-config)
                     #(sut/upsert! test-config [unit] [[0.1 0.2]] 42)
                     #(sut/delete! test-config "/proj/src/shop/orders.clj")
                     #(sut/search test-config [0.1 0.2] 10 #{})]]
    (is (thrown? Exception (operation)))))

(defn- with-search-server
  "Runs `f` with the port of a stand-in Qdrant that answers every search
   with `points` (none when not given) and records each request body in
   `requests`.

  Atom [Map] -> (Long -> IO a) -> IO a
  Atom [Map] -> [Map] -> (Long -> IO a) -> IO a"
  ([requests f] (with-search-server requests [] f))
  ([requests points f]
   (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)]
     (.createContext server "/collections/mcp-test-collection/points/search"
                     (reify HttpHandler
                       (handle [_this http-exchange]
                         (let [^HttpExchange exchange http-exchange
                               ^bytes body (.getBytes (json/write-str {:result points}) "UTF-8")]
                           (swap! requests conj (json/read-str (slurp (.getRequestBody exchange))
                                                               :key-fn keyword))
                           (.sendResponseHeaders exchange (int 200) (long (alength body)))
                           (with-open [out (.getResponseBody exchange)]
                             (.write out body))))))
     (.start server)
     (try
       (f (.getPort (.getAddress server)))
       (finally
         (.stop server 0))))))

(deftest search-leaves-out-the-points-of-excluded-files-in-qdrant
  (let [requests (atom [])]
    (with-search-server requests
      (fn [port]
        (sut/search (assoc test-config :qdrant/port port) [0.1 0.2] 10 #{"/proj/test/a_test.clj"})
        (sut/search (assoc test-config :qdrant/port port) [0.1 0.2] 10 #{})))
    (is (= [{:vector [0.1 0.2] :limit 10 :with_payload true :with_vector false
             :filter {:must_not [{:key "filename" :match {:any ["/proj/test/a_test.clj"]}}]}}
            {:vector [0.1 0.2] :limit 10 :with_payload true :with_vector false}]
           @requests))))

(defn- search-with-payload
  "Searches a stand-in Qdrant whose only point has `payload`; the
   exception thrown when it throws.

  Map -> IO (Maybe Exception)"
  [payload]
  (with-search-server (atom []) [{:id "00000000-0000-0000-0000-000000000001" :score 0.9 :payload payload}]
    (fn [port]
      (try
        (sut/search (assoc test-config :qdrant/port port) [0.1 0.2] 10 #{})
        nil
        (catch Exception e e)))))

(def ^:private stored-payload
  {:format sut/payload-format :model (config/setting test-config :ollama/embedding-model)
   :filename "/proj/src/shop/orders.clj" :mtime 42 :row 5 :kind "defn"
   :name "shop.orders/order-total" :text "shop.orders/order-total"})

(deftest search-says-the-collection-is-lost-when-it-holds-points-of-another-layout
  (is (nil? (search-with-payload stored-payload)))
  (is (sut/collection-lost? (search-with-payload (assoc stored-payload :format 2))))
  (is (sut/collection-lost? (search-with-payload (assoc stored-payload :model "another-model")))))

(deftest only-a-missing-or-mismatched-collection-is-lost
  (is (= [true true false false false false]
         (mapv sut/collection-lost?
               [(ex-info "Qdrant search failed" {:qdrant/status 404})
                (ex-info "Qdrant search failed"
                         {:qdrant/status 400
                          :body {:status {:error "Wrong input: Vector dimension error: expected dim: 1024, got 768"}}})
                (ex-info "Qdrant search failed" {:qdrant/status 400 :body {:status {:error "Bad filter"}}})
                (ex-info "Qdrant search failed" {:qdrant/status 500})
                (ex-info "Ollama: model not found" {:status 404})
                (ex-info "Connection refused" {})]))))

(deftest a-write-to-a-missing-collection-says-it-is-lost
  (with-search-server (atom [])
    (fn [port]
      (is (true? (try (sut/delete! (assoc test-config :qdrant/port port) "/proj/src/shop/orders.clj")
                      false
                      (catch Exception e (sut/collection-lost? e))))))))
