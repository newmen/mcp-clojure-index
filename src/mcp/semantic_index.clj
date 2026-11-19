(ns mcp.semantic-index
  "Keeps Qdrant's embeddings of Search units in step with the precise index.
   Embeddings are recomputed in the background after the freshness check;
   until they are, the files whose embeddings lag behind the precise index
   are stale (`stale-files`). Qdrant and Ollama are optional (ADR-0003):
   when either is down nothing here throws, the files simply stay stale."
  (:require [clojure.tools.logging :as log]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.search-units :as search-units]))

(set! *warn-on-reflection* true)

;; State shape (an atom, see `make-state`):
;;
;; {:embedded   {"/abs/file.clj" 1700000000000} ; file -> mtime its points were built from
;;  :prepared?  true                            ; collection checked and in the current format
;;  :failed-at  1700000000000                   ; last failed sync, absent after a success
;;  :latest-index {...}                         ; newest precise index handed to `sync-async!`
;;  :queued?    false                           ; a background sync is waiting to start
;;  :worker     <agent>}                        ; runs background syncs one at a time

(def ^:private retry-after-ms
  "A background sync is not retried sooner than this after a failure."
  30000)

(defn make-state
  "Fresh semantic index state: nothing is known to be embedded."
  []
  (atom {:embedded {}
         :prepared? false
         :worker (agent nil)}))

;; ---------------------------------------------------------------------------
;; Staleness
;; ---------------------------------------------------------------------------

(defn stale-files
  "Absolute paths of the files whose embeddings do not match `index`:
   files embedded from another mtime or not at all, and embedded files
   that are no longer indexed."
  [state index]
  (let [embedded (:embedded @state)
        indexed (:files index)]
    (vec (concat (remove #(= (get-in indexed [% :mtime]) (get embedded %)) (keys indexed))
                 (remove (partial contains? indexed) (keys embedded))))))

;; ---------------------------------------------------------------------------
;; Sync
;; ---------------------------------------------------------------------------

(defn prepare!
  "Make sure the collection exists in the current payload format, dropping
   a collection of an older format (or any, with `:qdrant/recreate?`), and
   learn which files it already holds."
  [cfg state]
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
  [cfg state index file-path units vectors]
  (qdrant/delete! cfg file-path)
  (if-let [mtime (get-in index [:files file-path :mtime])]
    (do (when (seq units)
          (qdrant/upsert! cfg units vectors mtime))
        (swap! state assoc-in [:embedded file-path] mtime))
    (swap! state update :embedded dissoc file-path)))

(defn sync!
  "Recompute and store the embeddings of the stale files of `index`,
   synchronously. Failures are logged and leave the files stale."
  [cfg state index]
  (try
    (when-not (:prepared? @state)
      (prepare! cfg state))
    (let [stale (stale-files state index)
          units (search-units/file-search-units index stale)
          vectors (embeddings/embed-units cfg units)
          by-file (group-by (comp :file first) (map vector units vectors))]
      (doseq [file-path stale
              :let [pairs (get by-file file-path)]]
        (store-file! cfg state index file-path (mapv first pairs) (mapv second pairs)))
      (when (seq stale)
        (log/info "Embeddings updated for" (count stale) "files"))
      (swap! state dissoc :failed-at))
    (catch Exception e
      (log/warn "Embeddings not updated, semantic search is stale:" (.getMessage e))
      (swap! state assoc :failed-at (System/currentTimeMillis)))))

(defn- retry-due?
  [state]
  (let [failed-at (:failed-at @state)]
    (or (nil? failed-at)
        (>= (- (System/currentTimeMillis) failed-at) retry-after-ms))))

(defn sync-async!
  "Start `sync!` in the background unless nothing is stale or the last
   attempt failed less than 30 seconds ago. Syncs run one at a time."
  [cfg state index]
  (swap! state assoc :latest-index index)
  (when (and (seq (stale-files state index))
             (retry-due? state)
             (not (:queued? (first (swap-vals! state assoc :queued? true)))))
    (send-off (:worker @state)
              (fn [_previous]
                (swap! state assoc :queued? false)
                (sync! cfg state (:latest-index @state))
                nil))))
