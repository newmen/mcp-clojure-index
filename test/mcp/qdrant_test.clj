(ns mcp.qdrant-test
  "The Qdrant adapter's point format, its search request and its behaviour
   with Qdrant down."
  (:require [clojure.data.json :as json]
            [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
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
                     #(sut/verify-layout! test-config true)
                     #(sut/embedded-files test-config)
                     #(sut/upsert! test-config [unit] [[0.1 0.2]] 42)
                     #(sut/delete! test-config "/proj/src/shop/orders.clj")
                     #(sut/search test-config [0.1 0.2] 10 #{})]]
    (is (thrown? Exception (operation)))))

(defn- with-qdrant
  "Runs `f` with the port of a stand-in Qdrant that answers a request to
   `path` under the test collection (\"\" for the collection itself) with
   the status and JSON body `answers` returns for the request body (nil
   when there is none), and 404 to any other path. Each request body is
   recorded in `requests`.

  Atom [Map] -> {String (Maybe Map -> [Long Map])} -> (Long -> IO a) -> IO a"
  [requests answers f]
  (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
        collection-path "/collections/mcp-test-collection"]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_this http-exchange]
                        (let [^HttpExchange exchange http-exchange
                              path (.getPath (.getRequestURI exchange))
                              request-text (slurp (.getRequestBody exchange))
                              request-body (when-not (st/blank? request-text)
                                             (json/read-str request-text :key-fn keyword))
                              answer (when (st/starts-with? path collection-path)
                                       (get answers (subs path (count collection-path))))
                              [status response-body] (if answer (answer request-body) [404 {:status {:error "Not found"}}])
                              ^bytes body (.getBytes (json/write-str response-body) "UTF-8")]
                          (swap! requests conj request-body)
                          (.sendResponseHeaders exchange (int status) (long (alength body)))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    (try
      (f (.getPort (.getAddress server)))
      (finally
        (.stop server 0)))))

(defn- on-port
  "The test config with Qdrant on `port`.

  Long -> Config"
  [port]
  (assoc test-config :qdrant/port port))

(deftest search-leaves-out-the-points-of-excluded-files-in-qdrant
  (let [requests (atom [])]
    (with-qdrant requests {"/points/search" (constantly [200 {:result []}])}
      (fn [port]
        (sut/search (on-port port) [0.1 0.2] 10 #{"/proj/test/a_test.clj"})
        (sut/search (on-port port) [0.1 0.2] 10 #{})))
    (is (= [{:vector [0.1 0.2] :limit 10 :with_payload true :with_vector false
             :filter {:must_not [{:key "filename" :match {:any ["/proj/test/a_test.clj"]}}]}}
            {:vector [0.1 0.2] :limit 10 :with_payload true :with_vector false}]
           @requests))))

(def ^:private current-layout
  (sut/current-layout test-config))

(def ^:private foreign-points-filter
  {:must_not [{:must [{:key "format" :match {:value sut/payload-format}}
                      {:key "model" :match {:value (:model current-layout)}}]}]})

(defn- collection-answers
  "The answers of a stand-in Qdrant whose collection holds points of the
   `layouts` (none: empty; nil: missing), filtering the scroll by the
   foreign points filter as Qdrant does.

  Maybe [CollectionLayout] -> {String (Maybe Map -> [Long Map])}"
  [layouts]
  (when layouts
    {"" (constantly [200 {:result {}}])
     "/points/scroll" (fn [{point-filter :filter}]
                        (let [matching (cond->> layouts
                                         (= foreign-points-filter point-filter) (remove #{current-layout}))]
                          [200 {:result {:points (vec (for [layout (take 1 matching)]
                                                        {:id "00000000-0000-0000-0000-000000000001"
                                                         :payload layout}))
                                         :next_page_offset nil}}]))}))

(defn- layout-outcome
  "What `stored-layout` reads and what `verify-layout!` does (`:ok` or
   `:lost`) for a collection holding points of `layouts` (nil: missing),
   by a server that stored points in it when `holds-points?`.

  Maybe [CollectionLayout] -> Boolean -> IO {:stored (Maybe CollectionLayout) :verify Keyword}"
  [layouts holds-points?]
  (with-qdrant (atom []) (collection-answers layouts)
    (fn [port]
      {:stored (sut/stored-layout (on-port port))
       :verify (try (sut/verify-layout! (on-port port) holds-points?)
                    :ok
                    (catch Exception e (if (sut/collection-lost? e) :lost :failed)))})))

(def ^:private foreign-layout
  {:format sut/payload-format :model "another-model"})

(deftest a-collection-of-the-current-layout-passes-the-check
  (is (= {:stored current-layout :verify :ok} (layout-outcome [current-layout current-layout] true))))

(deftest a-collection-with-any-point-of-another-layout-is-lost
  (testing "only foreign points"
    (is (= {:stored foreign-layout :verify :lost} (layout-outcome [foreign-layout] true))))
  (testing "foreign points behind current ones"
    (is (= {:stored foreign-layout :verify :lost} (layout-outcome [current-layout foreign-layout] true))))
  (testing "points of the old chunk format"
    (is (= {:stored {:format 1 :model nil} :verify :lost} (layout-outcome [{:format 1 :model nil}] true)))))

(deftest a-missing-or-empty-collection-is-lost-only-once-points-were-stored-in-it
  (is (= [{:stored nil :verify :lost} {:stored nil :verify :ok}
          {:stored nil :verify :lost} {:stored nil :verify :ok}]
         [(layout-outcome nil true) (layout-outcome nil false)
          (layout-outcome [] true) (layout-outcome [] false)])))

(deftest only-a-missing-or-mismatched-collection-is-lost
  (is (= [true true true false false false false]
         (mapv sut/collection-lost?
               [(ex-info "Qdrant search failed" {:qdrant/status 404})
                (ex-info "Qdrant search failed"
                         {:qdrant/status 400
                          :qdrant/body {:status {:error "Wrong input: Vector dimension error: expected dim: 1024, got 768"}}})
                (ex-info "Qdrant collection was deleted, emptied or replaced" {:qdrant/lost? true})
                (ex-info "Qdrant search failed" {:qdrant/status 400 :qdrant/body {:status {:error "Bad filter"}}})
                (ex-info "Qdrant search failed" {:qdrant/status 500})
                (ex-info "Ollama: model not found" {:status 404})
                (ex-info "Connection refused" {})]))))

(defn- write-outcome
  "Whether a write to a stand-in Qdrant answering with `answers` failed
   as a lost collection (`:lost`), otherwise (`:failed`) or not at all
   (`:ok`).

  {String (Maybe Map -> [Long Map])} -> (Config -> IO Any) -> IO Keyword"
  [answers write]
  (with-qdrant (atom []) answers
    (fn [port]
      (try (write (on-port port))
           :ok
           (catch Exception e (if (sut/collection-lost? e) :lost :failed))))))

(deftest a-write-to-a-missing-collection-says-it-is-lost
  (is (= :lost (write-outcome {} #(sut/delete! % "/proj/src/shop/orders.clj")))))

(deftest a-write-of-vectors-of-another-size-says-the-collection-is-lost
  (is (= [:lost :failed]
         (mapv (fn [error]
                 (write-outcome {"/points" (constantly [400 {:status {:error error}}])}
                                #(sut/upsert! % [unit] [[0.1 0.2]] 42)))
               ["Wrong input: Vector dimension error: expected dim: 1024, got 2"
                "Format error in JSON body"]))))
