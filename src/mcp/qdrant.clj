(ns mcp.qdrant
  (:require [clojure.data.json :as json]
            [clj-http.client :as http])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Qdrant URL helpers
;; ---------------------------------------------------------------------------

(defn- qdrant-base-url
  [cfg]
  (let [host (or (:qdrant/host cfg) "localhost")
        port (or (:qdrant/port cfg) 6333)]
    (str "http://" host ":" port)))

(defn- collection-url
  [cfg]
  (str (qdrant-base-url cfg) "/collections/" (:qdrant/collection cfg)))

(defn- points-url
  [cfg]
  (str (collection-url cfg) "/points"))

(defn- points-delete-url
  [cfg]
  (str (points-url cfg) "/delete"))

(defn- points-set-payload-url
  [cfg]
  (str (points-url cfg) "/set-payload"))

(defn- search-url
  [cfg]
  (str (points-url cfg) "/search"))

;; ---------------------------------------------------------------------------
;; Payload construction
;; ---------------------------------------------------------------------------

(def ^:private type-name-map
  {:fn :function
   :macro :macro
   :protocol :protocol
   :record :record
   :val :val
   :ns :ns
   :top-level :top-level
   :multimethod :multimethod})

(def ^:private name-type-map
  (into {} (map (fn [[k v]] [v k]) type-name-map)))

(defn- chunk->payload
  [chunk]
  {"id"         (str (:chunk/id chunk))
   "text"       (:chunk/source chunk)
   "language"   (:chunk/language chunk)
   "namespace"  (:chunk/ns chunk)
   "symbol"     (:chunk/name chunk)
   "type"       (name (get type-name-map (:chunk/type chunk) (:chunk/type chunk)))
   "visibility" (name (:chunk/visibility chunk))
   "file"       (:chunk/file chunk)
   "start_line" (:chunk/start-line chunk)
   "end_line"   (:chunk/end-line chunk)
   "hash"       (:chunk/hash chunk)})

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- http-get
  [url]
  (http/get url {:throw-exceptions false}))

(defn- http-put
  [url body-map]
  (http/put url {:content-type "application/json"
                 :body (json/write-str body-map)
                 :socket-timeout 30000
                 :connection-timeout 10000
                 :throw-exceptions false}))

(defn- http-post
  [url body-map]
  (http/post url {:content-type "application/json"
                  :body (json/write-str body-map)
                  :socket-timeout 30000
                  :connection-timeout 10000
                  :throw-exceptions false}))

(defn- parse-response
  [response]
  (let [body (when (:body response)
               (try (json/read-str (:body response) :key-fn keyword)
                    (catch Exception _ nil)))]
    {:status (:status response)
     :body body}))

;; ---------------------------------------------------------------------------
;; Collection management
;; ---------------------------------------------------------------------------

(defn- default-vector-config
  []
  {:size 768
   :distance "Cosine"})

(defn collection-exists?
  [cfg]
  (let [resp (http-put (str (collection-url cfg) "/exists") {})]
    (= 200 (:status resp))))

(defn create-collection!
  [cfg]
  (let [url (collection-url cfg)
        body {:name (:qdrant/collection cfg)
              :vectors (default-vector-config)}
        resp (http-put url body)
        {:keys [status body]} (parse-response resp)]
    (when (and (not= 200 status)
               (not= 409 status))
      (throw (ex-info "Failed to create Qdrant collection"
                      {:status status :body body :url url})))))

;; ---------------------------------------------------------------------------
;; Upsert points
;; ---------------------------------------------------------------------------

(defn- make-point
  [chunk embedding]
  {:id (str (:chunk/id chunk))
   :vector embedding
   :payload (chunk->payload chunk)})

(defn- batch-upsert!
  [cfg points]
  (let [url (points-url cfg)
        body {:points points}
        resp (http-put url body)
        {:keys [status body]} (parse-response resp)]
    (when (not= 200 status)
      (throw (ex-info "Qdrant upsert failed"
               {:status status :body body :url url
                :point-count (count points)})))))

(defn upsert!
  [cfg chunks embeddings]
  (let [pairs (map vector chunks embeddings)
        points (mapv (fn [[c e]] (make-point c e)) pairs)]
    (doseq [batch (partition-all 100 points)]
      (batch-upsert! cfg batch))))

;; ---------------------------------------------------------------------------
;; Delete points by file
;; ---------------------------------------------------------------------------

(defn delete!
  [cfg file-path]
  (let [url (points-delete-url cfg)
        body {:filter {:must [{:key "file"
                               :match {:value file-path}}]}}
        resp (http-post url body)
        {:keys [status body]} (parse-response resp)]
    (when (not= 200 status)
      (throw (ex-info "Qdrant delete failed"
               {:status status :body body :url url :file file-path})))))

;; ---------------------------------------------------------------------------
;; Update file path for all points belonging to a file
;; Used on rename when content hasn't changed
;; ---------------------------------------------------------------------------

(defn update-file-path!
  [cfg old-file-path new-file-path]
  (let [url (points-set-payload-url cfg)
        body {:filter {:must [{:key "file" :match {:value old-file-path}}]}
              :payload {:file new-file-path}}
        resp (http-post url body)
        {:keys [status body]} (parse-response resp)]
    (when (not= 200 status)
      (throw (ex-info "Qdrant update-file-path failed"
               {:status status :body body :url url
                :old-file old-file-path :new-file new-file-path})))))

;; ---------------------------------------------------------------------------
;; Point -> Chunk conversion (used by search)
;; ---------------------------------------------------------------------------

(defn- point->chunk
  [point]
  (let [p (:payload point)]
    {:chunk/id        (when-let [id-str (get p "id")]
                        (try (UUID/fromString id-str)
                             (catch Exception _ (UUID/randomUUID))))
     :chunk/source    (get p "text")
     :chunk/language  (get p "language")
     :chunk/ns        (get p "namespace")
     :chunk/name      (get p "symbol")
     :chunk/type      (when-let [t (get p "type")]
                        (get name-type-map (keyword t) (keyword t)))
     :chunk/visibility (when-let [v (get p "visibility")]
                         (keyword v))
     :chunk/file      (get p "file")
     :chunk/start-line (get p "start_line")
     :chunk/end-line   (get p "end_line")
     :chunk/hash      (get p "hash")}))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Collection info
;; ---------------------------------------------------------------------------

(defn collection-info
  [cfg]
  (try
    (let [url (collection-url cfg)
          resp (http-get url)
          {:keys [status body]} (parse-response resp)]
      (when (= 200 status)
        (let [count (get-in body [:result :points_count] 0)]
          {:points-count count})))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Scroll all points (payload only, no vectors)
;; ---------------------------------------------------------------------------

(defn- scroll-points
  [cfg limit offset]
  (let [url (str (collection-url cfg) "/points/scroll")
        body {:limit limit
              :offset offset
              :with_payload true
              :with_vector false}
        resp (http-post url body)
        {:keys [status body]} (parse-response resp)]
    (if (= 200 status)
      {:points (get body :points [])
       :next-page-offset (get body :next_page_offset)}
      (throw (ex-info "Qdrant scroll failed"
               {:status status :body body :url url})))))

(defn scroll-all
  [cfg]
  (try
    (let [batch-size 100]
      (loop [offset nil
             chunks []]
        (let [{:keys [points next-page-offset]} (scroll-points cfg batch-size offset)]
          (if (empty? points)
            chunks
            (let [new-chunks (mapv point->chunk points)]
              (recur next-page-offset (into chunks new-chunks)))))))
    (catch Exception _ nil)))

(defn search
  [cfg embedding top-k]
  (let [url (search-url cfg)
        body {:vector embedding
              :limit top-k
              :with_payload true
              :with_vector false}
        resp (http-post url body)
        {:keys [status body]} (parse-response resp)]
    (if (= 200 status)
      (let [result (:result body)]
        (mapv (fn [point]
                {:chunk (point->chunk point)
                 :score (get point :score 0.0)})
              result))
      (throw (ex-info "Qdrant search failed"
               {:status status :body body :url url})))))