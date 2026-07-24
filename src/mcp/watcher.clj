(ns mcp.watcher
  (:require [clojure.java.io :as io]
            [mcp.parser :as parser]
            [mcp.qdrant :as qdrant]
            [mcp.embeddings :as embeddings]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.config :as config])
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
  (let [lc (.toLowerCase path-str)
        has-ext (some #(.endsWith lc %) extensions)]
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
            remaining (remove (set (map :path old-enough)) (map :path events))
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
    (let [parse-result (parser/parse-file file-path)
          enriched (parser/enrich-chunks-with-ns parse-result)
          chunks (:result/chunks enriched)
          symbols (parser/extract-symbols enriched)]
      (if (empty? chunks)
        (do (println "[watcher] No chunks found in" file-path)
            {:index index :graph graph})
        (let [pairs (embeddings/generate-from-chunks cfg chunks)
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
              (println "[watcher] Error updating Qdrant for" file-path ":" (.getMessage e))))
          (println "[watcher] Re-indexed" file-path)
          {:index new-index :graph new-graph})))
    (catch Exception e
      (println "[watcher] Error processing" file-path ":" (.getMessage e))
      {:index index :graph graph})))

(defn process-delete
  [cfg index graph file-path]
  (let [new-index (si/remove-file! index file-path)
        new-graph (graph/remove-file! graph file-path)]
    (try
      (qdrant/delete! cfg file-path)
      (println "[watcher] Removed" file-path "from index")
      (catch Exception e
        (println "[watcher] Error deleting from Qdrant" file-path ":" (.getMessage e))))
    {:index new-index :graph new-graph}))

(defn process-rename-same-content
  [cfg index graph old-path new-path]
  (let [new-index (si/update-file-path! index old-path new-path)
        new-graph (graph/update-file-path! graph old-path new-path)]
    (try
      (qdrant/update-file-path! cfg old-path new-path)
      (println "[watcher] Renamed" old-path "->" new-path "(same content)")
      (catch Exception e
        (println "[watcher] Error updating Qdrant rename" old-path "->" new-path ":" (.getMessage e))))
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

(defn- watcher-loop
  [^WatchService watcher cfg index-ref graph-ref stop-atom]
  (let [extensions (into #{} (:index/include-extensions cfg))
        excludes (:index/exclude cfg)]
    (loop []
      (when (nil? @stop-atom)
        (let [^WatchKey key (try
                              (.poll watcher poll-timeout-ms TimeUnit/MILLISECONDS)
                              (catch Exception _ nil))
              keys-to-process (if (nil? key)
                                []
                                (let [^Path dir (.watchable key)]
                                  (try
                                    (let [events (->> (.pollEvents key)
                                                      (mapv (fn [^WatchEvent e]
                                                              (let [^Path ctx (.context e)
                                                                    full-path (.resolve dir ctx)]
                                                                (when (should-watch? (str full-path) extensions excludes)
                                                                  [e dir]))))
                                                      (filter some?))
                                          valid (.reset key)]
                                      (when (not valid)
                                        (println "[watcher] WatchKey no longer valid for" dir))
                                      events)
                                    (catch Exception _ []))))]
          ;; Process each event individually for now
          ;; TODO: Add proper debounce queue in future iteration
          (doseq [[^WatchEvent e ^Path dir] keys-to-process]
            (let [kind (.name (.kind e))
                  ^Path filename (.context e)
                  full-path (str (.toAbsolutePath (.resolve dir filename)))]
              (try
                (let [idx @index-ref
                      gr @graph-ref]
                  (cond
                    (#{"ENTRY_CREATE" "ENTRY_MODIFY"} kind)
                    (let [result (process-modify cfg idx gr full-path)]
                      (reset! index-ref (:index result))
                      (reset! graph-ref (:graph result)))
                    
                    (= kind "ENTRY_DELETE")
                    (let [result (process-delete cfg idx gr full-path)]
                      (reset! index-ref (:index result))
                      (reset! graph-ref (:graph result)))))
                (catch Exception ex
                  (println "[watcher] Error handling event:" (.getMessage ex))))))
          (recur))))))

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
                  (println "[watcher] Could not register" p-str ":" (.getMessage e)))))))))))

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
    (println "[watcher] Watching" root-path "for changes")
    (future (watcher-loop watcher cfg index-ref graph-ref stop-atom))
    {:watcher watcher :stop-atom stop-atom}))

(defn stop-watching!
  [watcher-map]
  (when watcher-map
    (when-let [stop-atom (:stop-atom watcher-map)]
      (reset! stop-atom true))
    (when-let [^WatchService ws (:watcher watcher-map)]
      (try (.close ws) (catch Exception _ nil)))
    (println "[watcher] Stopped")))

;; ---------------------------------------------------------------------------
;; Initial full index
;; ---------------------------------------------------------------------------

(defn reindex-all!
  "Perform a full re-index of the project.
   Returns {:index SymbolIndex :graph GraphResult}."
  [cfg]
  (let [root-path (:index/root-path cfg)
        excludes (:index/exclude cfg)
        _ (println "[watcher] Full re-index of" root-path "starting...")
        raw (parser/parse-project root-path excludes)
        enriched (parser/enrich-project-chunks raw)
        by-file (group-by :chunk/file (:chunks enriched))
        all-symbols (into []
                          (mapcat (fn [[_ chunks]]
                                    (let [ns-name (:chunk/ns (first chunks))]
                                      (keep #(parser/chunk->symbol-record % ns-name) chunks))))
                          by-file)
        all-chunks (:chunks enriched)
        index (si/build-index all-symbols all-chunks)
        _ (println "[watcher] Built symbol index:" (count all-symbols) "symbols")
        graph (graph/build-graph all-chunks index)
        _ (println "[watcher] Built dependency graph:" (count (:edges graph)) "edges")
        pairs (embeddings/generate-from-chunks cfg all-chunks)
        embeddings (mapv second pairs)
        chunks (mapv first pairs)
        _ (println "[watcher] Generated" (count embeddings) "embeddings")
        _ (println "[watcher] Uploading to Qdrant...")]
    (try
      (qdrant/upsert! cfg chunks embeddings)
      (println "[watcher] Full re-index complete")
      (catch Exception e
        (println "[watcher] Qdrant upload failed:" (.getMessage e))))
    {:index index :graph graph}))