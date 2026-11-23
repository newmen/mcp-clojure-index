(ns mcp.semantic-index
  "Keeps Qdrant's embeddings of Search units in step with the precise index.
   Embeddings are recomputed in the background after the freshness check;
   until they are, the files whose embeddings lag behind the precise index
   are stale (`stale-files`). Qdrant and Ollama are optional (ADR-0003):
   when either is down nothing here throws, the files simply stay stale."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [mcp.code-index :as code-index]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.search-units :as search-units]))

(set! *warn-on-reflection* true)

;; SemanticIndex: the config and the state atom, see `create`.
;;
;; SemanticState shape (the value of the atom):
;;
;; {:embedded   {"/abs/file.clj" 1700000000000} ; file -> mtime its points were built from
;;  :prepared?  true                            ; collection checked and in the current format
;;  :failed-at  1700000000000                   ; last failed sync, absent after a success
;;  :latest-index {...}                         ; newest precise index handed to `sync-async!`
;;  :queued?    false                           ; a background sync is waiting to start
;;  :worker     <agent>}                        ; runs background syncs one at a time

(s/def ::semantic-state
  (s/keys :req-un [::embedded ::prepared? ::worker]
          :opt-un [::failed-at ::latest-index ::queued?]))

(s/def ::state #(instance? clojure.lang.Atom %))

(s/def ::semantic-index (s/keys :req-un [:mcp.config/config ::state]))

(defrecord SemanticIndex [config state])

(def ^:private retry-after-ms
  "A background sync is not retried sooner than this after a failure.

  Long"
  30000)

(defn create
  "Creates a semantic index for `cfg` in which nothing is known to be
   embedded.

  Config -> IO SemanticIndex"
  [cfg]
  (->SemanticIndex cfg (atom {:embedded {}
                              :prepared? false
                              :worker (agent nil)})))

;; ---------------------------------------------------------------------------
;; Staleness
;; ---------------------------------------------------------------------------

(defn stale-files
  "Absolute paths of the files whose embeddings do not match `index`:
   files embedded from another mtime or not at all, and embedded files
   that are no longer indexed.

  SemanticIndex -> CodeIndex -> IO [String]"
  [{:keys [state]} index]
  (let [embedded (:embedded @state)
        indexed (code-index/file-mtimes index)]
    (vec (concat (remove #(= (get indexed %) (get embedded %)) (keys indexed))
                 (remove (partial contains? indexed) (keys embedded))))))

;; ---------------------------------------------------------------------------
;; Sync
;; ---------------------------------------------------------------------------

(defn prepare!
  "Makes sure the collection exists in the current payload format,
   dropping a collection of an older format (or any, with
   `:qdrant/recreate?`), and learns which files it already holds.

  SemanticIndex -> IO ()"
  [{cfg :config state :state}]
  (let [found-format (qdrant/stored-format cfg)
        current? (and (= qdrant/payload-format found-format)
                      (not (:qdrant/recreate? cfg)))]
    (when (and found-format (not current?))
      (log/info "Dropping Qdrant collection of payload format" found-format)
      (qdrant/delete-collection! cfg))
    (qdrant/create-collection! cfg)
    (swap! state assoc
           :embedded (if current? (qdrant/embedded-files cfg) {})
           :prepared? true)))

(defn- store-file!
  "Replaces the points of `file-path` with its Search `units` and their
   `vectors`, and records the mtime they were built from.

  SemanticIndex -> CodeIndex -> String -> [SearchUnit] -> [Embedding] -> IO ()"
  [{cfg :config state :state} index file-path units vectors]
  (qdrant/delete! cfg file-path)
  (if-let [mtime (get (code-index/file-mtimes index) file-path)]
    (do (when (seq units)
          (qdrant/upsert! cfg units vectors mtime))
        (swap! state assoc-in [:embedded file-path] mtime))
    (swap! state update :embedded dissoc file-path)))

(defn sync!
  "Recomputes and stores the embeddings of the stale files of `index`,
   synchronously. Failures are logged and leave the files stale.

  SemanticIndex -> CodeIndex -> IO ()"
  [{cfg :config state :state :as semantic-index} index]
  (try
    (when-not (:prepared? @state)
      (prepare! semantic-index))
    (let [stale (stale-files semantic-index index)
          units (search-units/file-search-units index stale)
          vectors (embeddings/embed-units cfg units)
          by-file (group-by (comp :filename first) (map vector units vectors))]
      (doseq [file-path stale
              :let [pairs (get by-file file-path)]]
        (store-file! semantic-index index file-path (mapv first pairs) (mapv second pairs)))
      (when (seq stale)
        (log/info "Embeddings updated for" (count stale) "files"))
      (swap! state dissoc :failed-at))
    (catch Exception e
      (log/warn "Embeddings not updated, semantic search is stale:" (.getMessage e))
      (swap! state assoc :failed-at (System/currentTimeMillis)))))

(defn- retry-due?
  "True unless the last sync failed less than `retry-after-ms` ago.

  Atom SemanticState -> IO Boolean"
  [state]
  (let [failed-at (:failed-at @state)]
    (or (nil? failed-at)
        (>= (- (System/currentTimeMillis) failed-at) retry-after-ms))))

(defn sync-async!
  "Starts `sync!` in the background unless nothing is stale or the last
   attempt failed less than 30 seconds ago. Syncs run one at a time.

  SemanticIndex -> CodeIndex -> IO ()"
  [{state :state :as semantic-index} index]
  (swap! state assoc :latest-index index)
  (when (and (seq (stale-files semantic-index index))
             (retry-due? state)
             (not (:queued? (first (swap-vals! state assoc :queued? true)))))
    (send-off (:worker @state)
              (fn [_previous]
                (swap! state assoc :queued? false)
                (sync! semantic-index (:latest-index @state))
                nil))))
