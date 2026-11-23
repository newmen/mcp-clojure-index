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
;;  :prepared?  true                            ; collection checked and in the current format;
;;                                              ; false again once it is found deleted or replaced
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
  "Makes sure the collection exists in the current payload format and for
   the configured embedding model, dropping any other collection: one of
   an older format or another model, an empty one (it may take vectors of
   another size), or any with `:qdrant/recreate?`; then learns which files
   it already holds. A new collection takes vectors of the size the model
   returns; only then is Ollama asked, so a current collection is used
   without it.

  SemanticIndex -> IO ()"
  [{cfg :config state :state}]
  (let [layout (qdrant/stored-layout cfg)
        current? (and (= (qdrant/current-layout cfg) layout)
                      (not (:qdrant/recreate? cfg)))]
    (when-not current?
      (let [vector-size (count (first (embeddings/generate ["vector size"] cfg)))]
        (when layout
          (log/info "Dropping Qdrant collection of" layout))
        (qdrant/delete-collection! cfg)
        (qdrant/create-collection! cfg vector-size)))
    (swap! state assoc
           :embedded (if current? (qdrant/embedded-files cfg) {})
           :prepared? true)))

(defn verify-collection!
  "Throws, as `qdrant/collection-lost?` recognises, when the prepared
   collection was deleted, emptied or replaced by one of another
   CollectionLayout (by another server instance or by hand). Does nothing
   while the collection is not prepared: `prepare!` checks it then.

  SemanticIndex -> IO ()"
  [{cfg :config state :state}]
  (let [{:keys [prepared? embedded]} @state]
    (when prepared?
      (qdrant/verify-layout! cfg (boolean (seq embedded))))))

(defn forget-if-collection-lost!
  "Forgets that the collection was prepared and what it holds when `e`
   says it was deleted, emptied or replaced, so every file is stale and
   the next sync prepares and fills it again. Returns true when it did,
   false when `e` is another failure and nothing changed.

  SemanticIndex -> Exception -> IO Boolean"
  [{state :state} e]
  (let [lost? (qdrant/collection-lost? e)]
    (when lost?
      (log/info "Qdrant collection was deleted or replaced, embedding every file again")
      (swap! state assoc :prepared? false :embedded {}))
    lost?))

(defn- store-file!
  "Replaces the points of `file-path` with its Search `units` and their
   `vectors`, and records the mtime they were built from, unless the
   collection was found lost meanwhile (by `semantic_search`): then the
   file stays stale, so the next sync stores it in the prepared collection.

  SemanticIndex -> CodeIndex -> String -> [SearchUnit] -> [Embedding] -> IO ()"
  [{cfg :config state :state} index file-path units vectors]
  (qdrant/delete! cfg file-path)
  (if-let [mtime (get (code-index/file-mtimes index) file-path)]
    (do (when (seq units)
          (qdrant/upsert! cfg units vectors mtime))
        (swap! state (fn [{:keys [prepared?] :as current}]
                       (cond-> current
                         prepared? (assoc-in [:embedded file-path] mtime)))))
    (swap! state update :embedded dissoc file-path)))

(defn- embed-stale-files!
  "Prepares the collection, or verifies the prepared one, then recomputes
   and stores the embeddings of the stale files of `index`. Throws on
   failure.

  SemanticIndex -> CodeIndex -> IO ()"
  [{cfg :config state :state :as semantic-index} index]
  (if (:prepared? @state)
    (verify-collection! semantic-index)
    (prepare! semantic-index))
  (let [stale (stale-files semantic-index index)
        units (search-units/file-search-units index stale)
        vectors (embeddings/embed-units cfg units)
        by-file (group-by (comp :filename first) (map vector units vectors))]
    (doseq [file-path stale
            :let [pairs (get by-file file-path)]]
      (store-file! semantic-index index file-path (mapv first pairs) (mapv second pairs)))
    (when (seq stale)
      (log/info "Embeddings updated for" (count stale) "files"))))

(defn sync!
  "Recomputes and stores the embeddings of the stale files of `index`,
   synchronously. Failures are logged and leave the files stale. One
   saying the collection was deleted, emptied or replaced makes every
   file stale and is retried at once, once: the retry prepares the
   collection and embeds every file.

  SemanticIndex -> CodeIndex -> IO ()"
  [{state :state :as semantic-index} index]
  (loop [attempts-left 2]
    (let [failure (try
                    (embed-stale-files! semantic-index index)
                    nil
                    (catch Exception e e))]
      (cond
        (nil? failure)
        (swap! state dissoc :failed-at)

        (and (forget-if-collection-lost! semantic-index failure) (> attempts-left 1))
        (recur (dec attempts-left))

        :else
        (do (log/warn "Embeddings not updated, semantic search is stale:" (.getMessage ^Exception failure))
            (swap! state assoc :failed-at (System/currentTimeMillis)))))))

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
