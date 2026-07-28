(ns mcp.watcher
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [mcp.parser :as parser]
            [mcp.qdrant :as qdrant]
            [mcp.embeddings :as embeddings]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph])
  (:import (java.nio.file FileSystems Files Path
                          WatchService StandardWatchEventKinds
                          WatchEvent WatchKey)
           (java.util.concurrent TimeUnit)
           (java.io File)
           (java.security MessageDigest)
           (java.math BigInteger)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private debounce-ms 150)

(def ^:private poll-timeout-ms 1000)

;; ---------------------------------------------------------------------------
;; File hashing
;; ---------------------------------------------------------------------------

(defn- sha-256-file
  [^String file-path]
  (try
    (let [digest (MessageDigest/getInstance "SHA-256")
          bytes (Files/readAllBytes (.toPath (io/file file-path)))]
      (.update digest bytes)
      (format "%064x" (BigInteger. 1 (.digest digest))))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; File type / extension checks
;; ---------------------------------------------------------------------------

(defn- should-watch?
  [^String path-str extensions exclude-regexes]
  (let [lc (s/lower-case path-str)
        has-ext (some #(s/ends-with? lc %) extensions)]
    (when (and has-ext (not-any? #(re-find % path-str) exclude-regexes))
      true)))

;; ---------------------------------------------------------------------------
;; Pending event queue with debounce coalescing
;; ---------------------------------------------------------------------------

(defrecord PendingEvent [kind path timestamp])

(defn- make-pending
  [kind ^String path]
  (->PendingEvent kind path (System/currentTimeMillis)))

;; Merge strategy: ENTRY_DELETE + ENTRY_CREATE for same path = MODIFY
;; ENTRY_DELETE (old) + ENTRY_CREATE (new) with debounce = RENAME
;; Otherwise latest wins per path

(defn- debounce-add
  [queue ^WatchEvent event ^Path dir]
  (let [kind (.name (.kind event))
        ^Path filename (.context event)
        full-path (str (.toAbsolutePath (.resolve dir filename)))
        now (System/currentTimeMillis)
        pending (:pending queue [])
        new-event (make-pending (keyword kind) full-path)]
    (assoc queue :pending (conj pending new-event) :last-event now)))

(defn- find-rename-pairs
  [events]
  (let [by-path (group-by :path events)]
    ;; Look for DELETE followed by CREATE of same path (rewrite on save)
    (reduce-kv (fn [acc path evts]
                 (let [kinds (set (map :kind evts))]
                   (if (and (kinds :ENTRY_DELETE) (kinds :ENTRY_CREATE))
                     (assoc acc path :modify)
                     acc)))
               {} by-path)))

(defn- find-cross-renames
  "Pairs of (DELETE old-path, CREATE new-path) within the debounce window."
  [events window-ms]
  (let [deletes (filter #(= :ENTRY_DELETE (:kind %)) events)
        creates (filter #(= :ENTRY_CREATE (:kind %)) events)]
    (when (and (seq deletes) (seq creates))
      ;; Heuristic: pair last DELETE with first CREATE within window
      (let [sorted-del (sort-by :timestamp > deletes)
            sorted-cre (sort-by :timestamp < creates)
            latest-del (first sorted-del)
            earliest-cre (first sorted-cre)]
        (when (and latest-del earliest-cre
                   (<= (- (:timestamp earliest-cre) (:timestamp latest-del)) window-ms))
          [{:delete (:path latest-del) :create (:path earliest-cre)}])))))

(defn- coalesce-events
  [queue]
  (let [events (:pending queue [])
        now (System/currentTimeMillis)
        window-ms 100
        old-enough (filter #(<= (- now (:timestamp %)) debounce-ms) events)]
    (if (empty? old-enough)
      {:actions [] :pending events}
      (let [renames (find-cross-renames old-enough window-ms)
            modify-pairs (find-rename-pairs old-enough)
            ;; Remove events that are part of rename/modify from standalone processing
            consumed (into #{} (apply concat
                               (for [r renames]
                                 [(:delete r) (:create r)])
                               (for [[p _] modify-pairs] [p])))
            standalone (remove #(contains? consumed (:path %)) old-enough)
            ;; Deduplicate standalone by kind+path (keep latest)
            deduped (vals (reduce (fn [acc evt]
                                    (assoc acc [(:kind evt) (:path evt)] evt))
                                  {} standalone))
            actions (concat
                      (for [[_ _] modify-pairs] {:action :modify})
                      (for [r renames] {:action :rename :old-file (:delete r) :new-file (:create r)})
                      (for [e deduped]
                        (let [kind (:kind e)]
                          (cond
                            (= kind :ENTRY_CREATE) {:action :modify :file (:path e)}
                            (= kind :ENTRY_DELETE) {:action :delete :file (:path e)}
                            (= kind :ENTRY_MODIFY) {:action :modify :file (:path e)}))))]
        {:actions (vec actions) :pending (vec (remove (set (map :path old-enough)) events))}))))

(defn- file-exists?
  [^String path]
  (.exists (io/file path)))

(defn- empty-queue
  []
  {:pending [] :last-event 0})

;; ---------------------------------------------------------------------------
;; Process a single file change event
;; ---------------------------------------------------------------------------

(defn process-modify
  [cfg index graph file-path]
  (try
    (let [file-hash (sha-256-file file-path)
          parse-result (parser/parse-file file-path)
          enriched (parser/enrich-chunks-with-ns parse-result)
          chunks (mapv #(assoc % :chunk/file-hash file-hash) (:result/chunks enriched))
          symbols (parser/extract-symbols {:result/chunks chunks :result/errors (:result/errors enriched)})]
      (if (empty? chunks)
        (do (log/warn "No chunks found in" file-path)
            (try (qdrant/delete! cfg file-path) (catch Exception _ nil))
            {:index (si/remove-file! index file-path)
             :graph (graph/remove-file! graph file-path)})
        (let [pairs (binding [embeddings/*config* cfg]
                      (embeddings/generate-from-chunks cfg chunks))
              embeddings (mapv second pairs)
              new-chunks (mapv first pairs)
              new-index (-> index
                            (si/remove-file! file-path)
                            (si/add-file! symbols))
              all-edges (mapcat #(graph/chunk->edges % new-index) new-chunks)
              new-graph (-> graph
                            (graph/remove-file! file-path)
                            (graph/add-file! (vec all-edges)))]
          (try
            (qdrant/delete! cfg file-path)
            (qdrant/upsert! cfg new-chunks embeddings)
            (catch Exception e
              (log/error e "Error updating Qdrant for" file-path ":" (.getMessage e))))
          (log/info "Re-indexed" file-path)
          {:index new-index :graph new-graph})))
    (catch Exception e
      (log/error e "Error processing" file-path ":" (.getMessage e))
      {:index index :graph graph})))

(defn process-delete
  [cfg index graph file-path]
  (let [new-index (si/remove-file! index file-path)
        new-graph (graph/remove-file! graph file-path)]
    (try
      (qdrant/delete! cfg file-path)
      (log/info "Removed" file-path "from index")
      (catch Exception e
        (log/error e "Error deleting from Qdrant" file-path ":" (.getMessage e))))
    {:index new-index :graph new-graph}))

(defn process-rename-same-content
  [cfg index graph old-path new-path]
  (let [new-index (si/update-file-path! index old-path new-path)
        new-graph (graph/update-file-path! graph old-path new-path)]
    (try
      (qdrant/update-file-path! cfg old-path new-path)
      (log/info "Renamed" old-path "->" new-path "(same content)")
      (catch Exception e
        (log/error e "Error updating Qdrant rename" old-path "->" new-path ":" (.getMessage e))))
    {:index new-index :graph new-graph}))

(defn process-rename
  [cfg index graph old-path new-path]
  (let [old-hash (when (file-exists? old-path) (sha-256-file old-path))
        new-hash (when (file-exists? new-path) (sha-256-file new-path))]
    (if (and old-hash new-hash (= old-hash new-hash))
      (process-rename-same-content cfg index graph old-path new-path)
      (let [del-result (process-delete cfg index graph old-path)]
        (process-modify cfg (:index del-result) (:graph del-result) new-path)))))

;; ---------------------------------------------------------------------------
;; Main watcher loop
;; ---------------------------------------------------------------------------

(defn- process-action
  [cfg index-ref graph-ref action]
  (let [idx @index-ref
        gr @graph-ref]
    (case (:action action)
      :modify
      (let [result (process-modify cfg idx gr (:file action))]
        (reset! index-ref (:index result))
        (reset! graph-ref (:graph result)))
      :delete
      (let [result (process-delete cfg idx gr (:file action))]
        (reset! index-ref (:index result))
        (reset! graph-ref (:graph result)))
      :rename
      (let [result (process-rename cfg idx gr (:old-file action) (:new-file action))]
        (reset! index-ref (:index result))
        (reset! graph-ref (:graph result))))))

(defn- drain-queue
  [queue cfg index-ref graph-ref]
  (let [{:keys [actions pending]} (coalesce-events queue)]
    (run! (partial process-action cfg index-ref graph-ref) actions)
    pending))

(defn- watcher-loop
  [^WatchService watcher cfg index-ref graph-ref stop-atom]
  (let [extensions (into #{} (:index/include-extensions cfg))
        excludes (:index/exclude cfg)]
    (loop [queue (empty-queue)]
      (if (some? @stop-atom)
        (drain-queue queue cfg index-ref graph-ref)
        (let [^WatchKey key (try
                              (.poll watcher poll-timeout-ms TimeUnit/MILLISECONDS)
                              (catch Exception _ nil))]
          (if (nil? key)
            (let [pending (drain-queue queue cfg index-ref graph-ref)]
              (recur {:pending pending :last-event (System/currentTimeMillis)}))
            (let [^Path dir (.watchable key)
                  new-queue (try
                              (reduce (fn [acc ^WatchEvent e]
                                        (let [^Path ctx (.context e)]
                                          (if (should-watch? (str (.resolve dir ctx)) extensions excludes)
                                            (debounce-add acc e dir)
                                            acc)))
                                      queue
                                      (.pollEvents key))
                              (catch Exception _ queue))]
              (when-not (.reset key)
                (log/warn "WatchKey no longer valid for" dir))
              (let [pending (drain-queue new-queue cfg index-ref graph-ref)]
                (recur {:pending pending :last-event (System/currentTimeMillis)})))))))))

;; ---------------------------------------------------------------------------
;; Register directory for watching (recursive)
;; ---------------------------------------------------------------------------

(defn- register-recursive
  [^WatchService watcher ^String root-path excludes]
  (let [root (io/file root-path)]
    (when (.exists root)
      (doseq [^File f (file-seq root)]
        (when (.isDirectory f)
          (let [p (.toPath f)
                p-str (.getPath ^File f)]
            ;; Skip excluded directories
            (when (not-any? #(re-find % p-str) excludes)
              (try
                (.register p watcher
                           (into-array java.nio.file.WatchEvent$Kind
                                       [StandardWatchEventKinds/ENTRY_CREATE
                                        StandardWatchEventKinds/ENTRY_DELETE
                                        StandardWatchEventKinds/ENTRY_MODIFY]))
                (catch Exception e
                  (log/warn "Could not register" p-str ":" (.getMessage e)))))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-watching!
  "Start watching the project directory for file changes.
   Returns {:watcher WatchService :stop-atom (atom nil)} for later use with stop-watching!.
   Updates the provided index and graph atoms as changes occur."
  [cfg index-ref graph-ref]
  (let [^WatchService watcher (-> (FileSystems/getDefault) (.newWatchService))
        root-path (:index/root-path cfg)
        excludes (:index/exclude cfg)
        stop-atom (atom nil)]
    (register-recursive watcher root-path excludes)
    (log/info "Watching" root-path "for changes")
    (future (watcher-loop watcher cfg index-ref graph-ref stop-atom))
    {:watcher watcher :stop-atom stop-atom}))

(defn stop-watching!
  [watcher-map]
  (when watcher-map
    (when-let [stop-atom (:stop-atom watcher-map)]
      (reset! stop-atom true))
    (when-let [^WatchService ws (:watcher watcher-map)]
      (try (.close ws) (catch Exception _ nil)))
    (log/info "Stopped")))

;; ---------------------------------------------------------------------------
;; Initial full index
;; ---------------------------------------------------------------------------

(defn reindex-all!
  "Perform a full re-index of the project.
   Returns {:index SymbolIndex :graph GraphResult}."
  [cfg]
  (let [root-path (:index/root-path cfg)
        excludes (:index/exclude cfg)
        _ (log/info "Full re-index of" root-path "starting...")
        raw (parser/parse-project root-path excludes)
        enriched (parser/enrich-project-chunks raw)
        by-file (group-by :chunk/file (:chunks enriched))
        all-chunks-with-hash (into []
                                    (mapcat (fn [[file chunks]]
                                              (let [file-hash (sha-256-file file)]
                                                (mapv #(assoc % :chunk/file-hash file-hash) chunks))))
                                    by-file)
        all-symbols (into []
                          (mapcat (fn [[_ chunks]]
                                    (let [ns-name (:chunk/ns (first chunks))]
                                      (keep #(parser/chunk->symbol-record % ns-name) chunks))))
                          by-file)
        all-chunks all-chunks-with-hash
        index (si/build-index all-symbols all-chunks)
        _ (log/info "Built symbol index:" (count all-symbols) "symbols")
        graph (graph/build-graph all-chunks index)
        _ (log/info "Built dependency graph:" (count (:edges graph)) "edges")
        pairs (binding [embeddings/*config* cfg]
                      (embeddings/generate-from-chunks cfg all-chunks))
        embeddings (mapv second pairs)
        chunks (mapv first pairs)
        _ (log/info "Generated" (count embeddings) "embeddings")
        _ (log/info "Creating Qdrant collection if needed...")]
    (try
      (qdrant/create-collection! cfg)
      (log/info "Uploading to Qdrant...")
      (qdrant/upsert! cfg chunks embeddings)
      (log/info "Full re-index complete")
      (catch Exception e
        (log/error e "Qdrant operation failed:" (.getMessage e))))
    {:index index :graph graph}))

;; ---------------------------------------------------------------------------
;; State restoration from Qdrant
;; ---------------------------------------------------------------------------

(defn- collect-project-files
  [cfg]
  (let [root-path (:index/root-path cfg)
        excludes (:index/exclude cfg)
        extensions (into #{} (:index/include-extensions cfg))]
    (reduce (fn [acc ^File f]
              (let [path (.getPath f)]
                (if (and (.isFile f)
                         (some #(s/ends-with? (s/lower-case path) %) extensions)
                         (not-any? #(re-find % path) excludes))
                  (let [h (sha-256-file path)]
                    (if h
                      (assoc acc path h)
                      acc))
                  acc)))
            {}
            (file-seq (io/file root-path)))))

(defn- file->hash-map
  [chunks]
  (persistent!
    (reduce (fn [acc chunk]
              (let [f (:chunk/file chunk)
                    h (:chunk/file-hash chunk)]
                (if h
                  (assoc! acc f h)
                  acc)))
            (transient {})
            chunks)))

(defn- chunks->symbols
  [chunks]
  (let [by-file (group-by :chunk/file chunks)]
    (into []
          (mapcat (fn [[_file file-chunks]]
                    (let [ns-name (:chunk/ns (first file-chunks))]
                      (keep #(parser/chunk->symbol-record % ns-name) file-chunks))))
          by-file)))

(defn restore-state!
  [cfg]
  (if (:qdrant/recreate? cfg)
    (do (log/info ":qdrant/recreate? is true, performing full re-index")
        (reindex-all! cfg))
    (let [info (qdrant/collection-info cfg)]
      (if (or (nil? info) (zero? (:points-count info 0)))
        (do (log/info "Qdrant collection is empty or does not exist, performing full re-index")
            (reindex-all! cfg))
        (let [chunks (qdrant/scroll-all cfg)]
          (if (nil? chunks)
            (do (log/warn "scroll-all returned nil, performing full re-index")
                (reindex-all! cfg))
            (let [chunks-vec (vec chunks)]
              (log/info "Restored" (count chunks-vec) "chunks from Qdrant")
              (let [symbols (chunks->symbols chunks-vec)
                    index (si/build-index symbols chunks-vec)
                    graph (graph/build-graph chunks-vec index)
                    _ (log/info "Built symbol index:" (count symbols) "symbols")
                    _ (log/info "Built dependency graph:" (count (:edges graph)) "edges")
                    stored-hashes (file->hash-map chunks-vec)
                    fs-hashes (collect-project-files cfg)
                    all-fs-paths (set (keys fs-hashes))
                    all-stored-paths (set (keys stored-hashes))
                    changed (filter (fn [path]
                                      (not= (get fs-hashes path)
                                            (get stored-hashes path)))
                                    (set/intersection all-fs-paths all-stored-paths))
                    new-files (set/difference all-fs-paths all-stored-paths)
                    deleted (set/difference all-stored-paths all-fs-paths)
                    _ (log/info "Syncing:" (count changed) "changed,"
                                (count new-files) "new," (count deleted) "deleted files")
                    index-atom (atom index)
                    graph-atom (atom graph)]
                (doseq [file-path deleted]
                  (let [result (process-delete cfg @index-atom @graph-atom file-path)]
                    (reset! index-atom (:index result))
                    (reset! graph-atom (:graph result))))
                (doseq [file-path (concat changed new-files)]
                  (let [result (process-modify cfg @index-atom @graph-atom file-path)]
                    (reset! index-atom (:index result))
                    (reset! graph-atom (:graph result))))
                (log/info "State restoration complete")
                {:index @index-atom :graph @graph-atom}))))))))