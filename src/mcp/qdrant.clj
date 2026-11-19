(ns mcp.qdrant
  "Qdrant HTTP adapter: one point per Search unit (see `mcp.search-units`).
   Qdrant holds embeddings only, never the precise index (ADR-0003)."
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]))

(set! *warn-on-reflection* true)

;; Point payload (string keys, as stored):
;;
;; {"format" 3                        ; `payload-format`
;;  "filename" "/abs/src/shop/orders.clj"
;;  "mtime"  1700000000000            ; mtime of the file the unit was built from
;;  "row"    5
;;  "kind"   "defn"
;;  "name"   "shop.orders/order-total"
;;  "doc"    "First line.\n More."   ; whole docstring, absent when there is none
;;  "text"   "..."}                   ; the embedding text, also fed to the reranker

(def payload-format
  "Version of the point payload; a collection holding another version is
   dropped and recreated."
  3)

(def ^:private vector-config
  {:size 768
   :distance "Cosine"})

(def ^:private upsert-batch-size 100)

(def ^:private scroll-batch-size 1000)

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- collection-url
  [cfg]
  (str "http://" (or (:qdrant/host cfg) "localhost") ":" (or (:qdrant/port cfg) 6333)
       "/collections/" (:qdrant/collection cfg)))

(defn- request
  [method url body-map]
  (let [response (http/request (cond-> {:method method
                                        :url url
                                        :socket-timeout 30000
                                        :connection-timeout 5000
                                        :throw-exceptions false}
                                 body-map (assoc :content-type "application/json"
                                                 :body (json/write-str body-map))))]
    {:status (:status response)
     :body (try (json/read-str (:body response) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- request!
  "Like `request`, but throws unless Qdrant answers with one of `statuses`."
  [method url body-map description statuses]
  (let [{:keys [status] :as response} (request method url body-map)]
    (when-not (contains? statuses status)
      (throw (ex-info (str "Qdrant " description " failed")
                      {:status status :body (:body response) :url url})))
    response))

;; ---------------------------------------------------------------------------
;; Collection
;; ---------------------------------------------------------------------------

(defn create-collection!
  "Create the collection unless it exists."
  [cfg]
  (request! :put (collection-url cfg) {:vectors vector-config} "create collection" #{200 409}))

(defn delete-collection!
  "Drop the collection with all its points."
  [cfg]
  (request! :delete (collection-url cfg) nil "delete collection" #{200 404}))

(defn- scroll
  [cfg page-offset limit payload-keys]
  (let [{:keys [body]} (request! :post (str (collection-url cfg) "/points/scroll")
                                 (cond-> {:limit limit
                                          :with_payload payload-keys
                                          :with_vector false}
                                   page-offset (assoc :offset page-offset))
                                 "scroll" #{200})]
    {:points (get-in body [:result :points])
     :next (get-in body [:result :next_page_offset])}))

(defn stored-format
  "Payload format of the points in the collection: nil when it is missing
   or empty, 1 for points of the old chunk format."
  [cfg]
  (let [{:keys [status]} (request :get (collection-url cfg) nil)]
    (if (= 404 status)
      nil
      (some-> (first (:points (scroll cfg nil 1 ["format"])))
              (get-in [:payload :format] 1)))))

(defn embedded-files
  "{file mtime} of the files whose points the collection holds; a file
   whose points disagree on the mtime is left out."
  [cfg]
  (loop [page-offset nil
         mtimes-by-file {}]
    (let [{:keys [points next]} (scroll cfg page-offset scroll-batch-size ["filename" "mtime"])
          mtimes-by-file (reduce (fn [acc {{:keys [filename mtime]} :payload}]
                                   (update acc filename (fnil conj #{}) mtime))
                                 mtimes-by-file
                                 points)]
      (if next
        (recur next mtimes-by-file)
        (into {}
              (keep (fn [[file mtimes]]
                      (when (= 1 (count mtimes)) [file (first mtimes)])))
              mtimes-by-file)))))

;; ---------------------------------------------------------------------------
;; Points
;; ---------------------------------------------------------------------------

(defn- unit->point
  [mtime {:keys [id filename row kind doc text] unit-name :name} unit-vector]
  {:id (str id)
   :vector unit-vector
   :payload (cond-> {"format" payload-format
                     "filename" filename
                     "mtime" mtime
                     "row" row
                     "kind" kind
                     "name" unit-name
                     "text" text}
              doc (assoc "doc" doc))})

(defn- point->unit
  [{:keys [id payload]}]
  (cond-> (select-keys payload [:filename :row :kind :name :text])
    true (assoc :id (str id))
    (:doc payload) (assoc :doc (:doc payload))))

(defn upsert!
  "Store the Search `units` of one file, built from its `mtime`, with
   their `unit-vectors`."
  [cfg units unit-vectors mtime]
  (doseq [batch (partition-all upsert-batch-size (map (partial unit->point mtime) units unit-vectors))]
    (request! :put (str (collection-url cfg) "/points?wait=true") {:points batch} "upsert" #{200})))

(defn delete!
  "Remove all points of `file-path`."
  [cfg file-path]
  (request! :post (str (collection-url cfg) "/points/delete?wait=true")
            {:filter {:must [{:key "filename" :match {:value file-path}}]}}
            "delete" #{200}))

(defn search
  "The `top-k` points nearest to `query-vector`: [{:unit unit :score s}]."
  [cfg query-vector top-k]
  (->> (request! :post (str (collection-url cfg) "/points/search")
                 {:vector query-vector :limit top-k :with_payload true :with_vector false}
                 "search" #{200})
       :body
       :result
       (mapv (fn [point] {:unit (point->unit point) :score (:score point 0.0)}))))
