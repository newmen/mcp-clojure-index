(ns mcp.qdrant
  "Qdrant HTTP adapter: one point per Search unit (see `mcp.search-units`).
   Qdrant holds embeddings only, never the precise index (ADR-0003)."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clj-http.client :as http]
            [mcp.config :as config]))

(set! *warn-on-reflection* true)

;; Point payload (string keys, as stored):
;;
;; {"format" 3                        ; `payload-format`
;;  "model"  "bge-m3"                 ; the embedding model of the vector
;;  "filename" "/abs/src/shop/orders.clj"
;;  "mtime"  1700000000000            ; mtime of the file the unit was built from
;;  "row"    5
;;  "kind"   "defn"
;;  "name"   "shop.orders/order-total"
;;  "doc"    "First line.\n More."   ; whole docstring, absent when there is none
;;  "text"   "..."}                   ; the embedding text, also fed to the reranker
;;
;; A Point is {:id :vector :payload}; points read back have keyword payload
;; keys and no vector.
;; A ScoredUnit is a Search unit found by `search`, {:unit :score}.
;; A CollectionLayout is what the points of a collection were stored with,
;; {:format :model}; :model is nil for points stored before it was recorded.
;; A ScrollPage is one page of points and the offset of the next page,
;; {:points :next}; :next is nil on the last page.

(s/def ::point (s/keys :req-un [::id ::payload] :opt-un [::vector]))

(s/def ::scored-unit (s/keys :req-un [::unit ::score]))

(s/def ::collection-layout (s/keys :req-un [::format ::model]))

(s/def ::scroll-page (s/keys :req-un [::points ::next]))

(def payload-format
  "Version of the point payload; a collection holding another version is
   dropped and recreated.

  Long"
  3)

(def ^:private upsert-batch-size 100)

(def ^:private scroll-batch-size 1000)

(def ^:private vector-size-error
  "What Qdrant's error message of a 400 answer contains when the
   collection takes vectors of another size.

  Pattern"
  #"(?i)vector dimension")

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- collection-url
  "The URL of the configured Qdrant collection.

  Config -> String"
  [cfg]
  (str "http://" (config/setting cfg :qdrant/host) ":" (config/setting cfg :qdrant/port)
       "/collections/" (:qdrant/collection cfg)))

(defn- request
  "Sends an HTTP request to Qdrant, with `body-map` as JSON when given.

  Keyword -> String -> Maybe Map -> IO JsonResponse"
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
  "Sends a request like `request`, but throws unless Qdrant answers with
   one of `statuses`.

  Keyword -> String -> Maybe Map -> String -> #{Long} -> IO JsonResponse"
  [method url body-map description statuses]
  (let [{:keys [status] :as response} (request method url body-map)]
    (when-not (contains? statuses status)
      (throw (ex-info (str "Qdrant " description " failed")
                      {:qdrant/status status :body (:body response) :url url})))
    response))

(defn collection-lost?
  "True when `e` was thrown by an operation of this namespace because the
   collection was deleted (404) or replaced by one of another layout: its
   points were stored with another CollectionLayout, or it takes vectors
   of another size (400). Failures of other services are never this.

  Exception -> Boolean"
  [e]
  (let [{:keys [foreign-layout body] status :qdrant/status} (ex-data e)]
    (boolean (or foreign-layout
                 (= 404 status)
                 (and (= 400 status)
                      (re-find vector-size-error (str (get-in body [:status :error]))))))))

;; ---------------------------------------------------------------------------
;; Collection
;; ---------------------------------------------------------------------------

(defn create-collection!
  "Creates the collection for vectors of `vector-size` unless it exists.

  Config -> Long -> IO JsonResponse"
  [cfg vector-size]
  (request! :put (collection-url cfg) {:vectors {:size vector-size :distance "Cosine"}}
            "create collection" #{200 409}))

(defn delete-collection!
  "Drops the collection with all its points.

  Config -> IO JsonResponse"
  [cfg]
  (request! :delete (collection-url cfg) nil "delete collection" #{200 404}))

(defn- scroll
  "Reads one page of at most `limit` points, with the payload keys
   `payload-keys`, starting at `page-offset` (nil for the first page).

  Config -> Maybe String -> Long -> [String] -> IO ScrollPage"
  [cfg page-offset limit payload-keys]
  (let [{:keys [body]} (request! :post (str (collection-url cfg) "/points/scroll")
                                 (cond-> {:limit limit
                                          :with_payload payload-keys
                                          :with_vector false}
                                   page-offset (assoc :offset page-offset))
                                 "scroll" #{200})]
    {:points (get-in body [:result :points])
     :next (get-in body [:result :next_page_offset])}))

(defn current-layout
  "The CollectionLayout this server stores points with: the current
   payload format and the configured embedding model.

  Config -> CollectionLayout"
  [cfg]
  {:format payload-format
   :model (config/setting cfg :ollama/embedding-model)})

(defn- point-layout
  "The CollectionLayout a Point was stored with: format 1 for points of
   the old chunk format.

  Point -> CollectionLayout"
  [{:keys [payload]}]
  {:format (:format payload 1)
   :model (:model payload)})

(defn stored-layout
  "Reads the payload format and embedding model of the points in the
   collection: nil when it is missing or empty, format 1 for points of the
   old chunk format.

  Config -> IO (Maybe CollectionLayout)"
  [cfg]
  (let [{:keys [status]} (request :get (collection-url cfg) nil)]
    (when-not (= 404 status)
      (some-> (first (:points (scroll cfg nil 1 ["format" "model"]))) point-layout))))

(defn embedded-files
  "Reads the mtime of each file whose points the collection holds; a file
   whose points disagree on the mtime is left out.

  Config -> IO {String Long}"
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
  "The Point of a Search unit built from a file of `mtime`, with its
   vector from the embedding model `model`.

  String -> Long -> SearchUnit -> Embedding -> Point"
  [model mtime {:keys [id filename row kind doc text] unit-name :name} unit-vector]
  {:id (str id)
   :vector unit-vector
   :payload (cond-> {"format" payload-format
                     "model" model
                     "filename" filename
                     "mtime" mtime
                     "row" row
                     "kind" kind
                     "name" unit-name
                     "text" text}
              doc (assoc "doc" doc))})

(defn- point->unit
  "The Search unit stored in a Point read back from Qdrant.

  Point -> SearchUnit"
  [{:keys [id payload]}]
  (cond-> (select-keys payload [:filename :row :kind :name :text])
    true (assoc :id (str id))
    (:doc payload) (assoc :doc (:doc payload))))

(defn upsert!
  "Stores the Search `units` of one file, built from its `mtime`, with
   their `unit-vectors` from the configured embedding model.

  Config -> [SearchUnit] -> [Embedding] -> Long -> IO ()"
  [cfg units unit-vectors mtime]
  (doseq [batch (partition-all upsert-batch-size (map (partial unit->point (config/setting cfg :ollama/embedding-model) mtime) units unit-vectors))]
    (request! :put (str (collection-url cfg) "/points?wait=true") {:points batch} "upsert" #{200})))

(defn delete!
  "Removes all points of `file-path`.

  Config -> String -> IO JsonResponse"
  [cfg file-path]
  (request! :post (str (collection-url cfg) "/points/delete?wait=true")
            {:filter {:must [{:key "filename" :match {:value file-path}}]}}
            "delete" #{200}))

(defn search
  "Finds the `top-k` points nearest to `query-vector`, leaving out in
   Qdrant the points of `excluded-files` (absolute paths), so they take
   none of the `top-k` places. Throws, as `collection-lost?` recognises,
   when a point found was stored with another CollectionLayout.

  Config -> Embedding -> Long -> #{String} -> IO [ScoredUnit]"
  [cfg query-vector top-k excluded-files]
  (let [points (-> (request! :post (str (collection-url cfg) "/points/search")
                             (cond-> {:vector query-vector :limit top-k :with_payload true :with_vector false}
                               (seq excluded-files)
                               (assoc :filter {:must_not [{:key "filename" :match {:any (vec (sort excluded-files))}}]}))
                             "search" #{200})
                   (get-in [:body :result]))
        layout (current-layout cfg)]
    (when-let [foreign (->> points (map point-layout) (remove #{layout}) first)]
      (throw (ex-info "Qdrant collection holds points of another layout"
                      {:foreign-layout foreign :url (collection-url cfg)})))
    (mapv (fn [point] {:unit (point->unit point) :score (:score point 0.0)}) points)))
