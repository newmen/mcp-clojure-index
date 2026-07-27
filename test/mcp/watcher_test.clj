(ns mcp.watcher-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [mcp.watcher :as sut]
            [mcp.parser :as parser]
            [mcp.embeddings :as embeddings]
            [mcp.symbol-index :as si]
            [mcp.graph :as graph]
            [mcp.config :as config])
  (:import (java.nio.file StandardWatchEventKinds)))

(set! *warn-on-reflection* true)

(def fixtures-dir "test-resources/fixtures")

(defn temp-dir
  []
  (let [d (io/file (str "/tmp/mcp-watcher-test-" (java.util.UUID/randomUUID)))]
    (.mkdirs d)
    d))

(defn- copy-fixture
  [fixture-name ^java.io.File target-dir]
  (let [src (io/file (str fixtures-dir "/" fixture-name))
        dest (io/file target-dir fixture-name)]
    (io/copy src dest)
    dest))

(defn- make-symbol
  [sym-name file-path]
  {:sym/name       (symbol (str "test.ns/" (name sym-name)))
   :sym/simple     sym-name
   :sym/ns         'test.ns
   :sym/type       :fn
   :sym/file       file-path
   :sym/line       1
   :sym/arglists   nil
   :sym/doc        nil
   :sym/protocol   nil
   :sym/record     nil
   :sym/tag        nil
   :sym/chunk-id   (java.util.UUID/randomUUID)
   :sym/visibility :public
   :sym/aliases    #{}})

(defn- make-edge
  [from-sym to-sym file-path from-id to-id]
  {:edge/from    from-sym
   :edge/from-id from-id
   :edge/to      to-sym
   :edge/to-id   to-id
   :edge/type    :call
   :edge/file    file-path
   :edge/line    1})

;; ---------------------------------------------------------------------------
;; sha-256-file
;; ---------------------------------------------------------------------------

(deftest sha-256-file-on-existing-file
  (let [d (temp-dir)
        f (io/file d "test.clj")]
    (spit f "(defn foo [x] x)")
    (let [hash (#'sut/sha-256-file (.getPath f))]
      (is (string? hash))
      (is (= 64 (count hash))))
    (io/delete-file f true)
    (io/delete-file d true)))

(deftest sha-256-file-on-missing-file
  (is (nil? (#'sut/sha-256-file "/nonexistent/path.clj"))))

;; ---------------------------------------------------------------------------
;; should-watch?
;; ---------------------------------------------------------------------------

(deftest should-watch-clj-files
  (is (#'sut/should-watch? "/project/src/core.clj" [".clj"] []))
  (is (#'sut/should-watch? "/project/src/core.cljc" [".cljc"] []))
  (is (#'sut/should-watch? "/project/src/core.cljs" [".cljs"] []))
  (is (#'sut/should-watch? "/project/config.edn" [".edn"] [])))

(deftest should-watch-excludes
  (is (nil? (#'sut/should-watch? "/project/target/classes/core.clj"
                                   [".clj"] [#"target"])))
  (is (nil? (#'sut/should-watch? "/project/.git/objects/core.clj"
                                   [".clj"] [#"\.git"]))))

(deftest should-watch-non-clojure-files
  (is (nil? (#'sut/should-watch? "/project/README.md" [".clj"] [])))
  (is (nil? (#'sut/should-watch? "/project/pom.xml" [".clj"] []))))

;; ---------------------------------------------------------------------------
;; Debounce queue
;; ---------------------------------------------------------------------------

(deftest debounce-add-creates-pending-event
  (let [queue (#'sut/empty-queue)
        dir (.toPath (io/file "/tmp"))
        mock-event (reify java.nio.file.WatchEvent
                     (kind [_] StandardWatchEventKinds/ENTRY_CREATE)
                     (context [_] (.toPath (io/file "test.clj"))))
        result (#'sut/debounce-add queue mock-event dir)]
    (is (= 1 (count (:pending result))))))

(deftest coalesce-events-empty-queue
  (let [result (#'sut/coalesce-events (#'sut/empty-queue))]
    (is (= [] (:actions result)))))

(deftest coalesce-returns-modify-for-delete-then-create-on-same-path
  (let [now (System/currentTimeMillis)
        queue {:pending [(#'sut/make-pending :ENTRY_DELETE "/tmp/test.clj")
                         (#'sut/make-pending :ENTRY_CREATE "/tmp/test.clj")]
               :last-event now}
        result (#'sut/coalesce-events queue)]
    (is (some #(= :modify (:action %)) (:actions result)))))

(deftest coalesce-returns-rename-for-cross-path-events
  (let [now (System/currentTimeMillis)
        queue {:pending [(#'sut/make-pending :ENTRY_DELETE "/tmp/old.clj")
                         (#'sut/make-pending :ENTRY_CREATE "/tmp/new.clj")]
               :last-event now}
        result (#'sut/coalesce-events queue)]
    (is (some #(= :rename (:action %)) (:actions result)))
    (let [rename (first (filter #(= :rename (:action %)) (:actions result)))]
      (is (= "/tmp/old.clj" (:old-file rename)))
      (is (= "/tmp/new.clj" (:new-file rename))))))

;; ---------------------------------------------------------------------------
;; process-delete removes from index, graph, qdrant (throws on Qdrant unreachable)
;; ---------------------------------------------------------------------------

(deftest process-delete-removes-from-index-and-graph
  (let [file-path "/tmp/test-delete.clj"
        cfg (assoc config/defaults :qdrant/port 19999 :embedding/cache-dir "/tmp/mcp-test-watcher-cache-delete")
        sym (make-symbol 'test-func file-path)
        idx (-> (si/empty-index)
                (si/add-file! [sym]))
        e (make-edge 'test.ns/test-func 'test.ns/other-func file-path
                     (:sym/chunk-id sym) (java.util.UUID/randomUUID))
        gr (-> (graph/empty-graph)
               (graph/add-file! [e]))
        {:keys [index graph]} (sut/process-delete cfg idx gr file-path)]
    (is (empty? (:index/by-qname index)))
    (is (empty? (:by-file graph)))))

(deftest process-delete-noop-for-unknown-file
  (let [cfg (assoc config/defaults :qdrant/port 19999)
        idx (si/empty-index)
        gr (graph/empty-graph)
        result (sut/process-delete cfg idx gr "/nonexistent.clj")]
    (is (= idx (:index result)))
    (is (= gr (:graph result)))))

;; ---------------------------------------------------------------------------
;; process-modify re-indexes a file
;; ---------------------------------------------------------------------------

(deftest process-modify-reindexes-valid-file
  (let [^java.io.File d (temp-dir)
        ^java.io.File f (copy-fixture "valid_defs.clj" d)
        file-path (.getPath f)
        cfg (assoc config/defaults
                   :qdrant/port 19999
                   :index/root-path (.getPath d)
                   :embedding/cache-dir "/tmp/mcp-test-watcher-cache-modify")
        idx (si/empty-index)
        gr (graph/empty-graph)
        result (sut/process-modify cfg idx gr file-path)]
    (is (map? result))
    (is (contains? result :index))
    (is (contains? result :graph))
    (io/delete-file f true)
    (io/delete-file d true)))

;; ---------------------------------------------------------------------------
;; process-rename delegates correctly
;; ---------------------------------------------------------------------------

(deftest process-rename-returns-map
  (let [^java.io.File d (temp-dir)
        ^java.io.File f-src (copy-fixture "valid_defs.clj" d)
        old-path (.getPath f-src)
        new-path (str (.getPath d) "/renamed.clj")
        cfg (assoc config/defaults
                   :qdrant/port 19999
                   :embedding/cache-dir "/tmp/mcp-test-watcher-cache-rename-same")
        idx (si/empty-index)
        gr (graph/empty-graph)]
    (io/copy f-src (io/file new-path))
    (let [result (sut/process-rename cfg idx gr old-path new-path)]
      (is (map? result))
      (is (contains? result :index))
      (is (contains? result :graph)))
    (io/delete-file f-src true)
    (io/delete-file (io/file new-path) true)
    (io/delete-file d true)))

;; ---------------------------------------------------------------------------
;; update-file-path! in symbol-index and graph
;; ---------------------------------------------------------------------------

(deftest symbol-index-update-file-path
  (let [old-path "/old/path.clj"
        new-path "/new/path.clj"
        sym (make-symbol 'test-func old-path)
        idx (-> (si/empty-index)
                (si/add-file! [sym]))]
    (is (contains? (:index/by-file idx) old-path))
    (let [updated (si/update-file-path! idx old-path new-path)]
      (is (not (contains? (:index/by-file updated) old-path)))
      (is (contains? (:index/by-file updated) new-path))
      (is (= new-path (:sym/file (first (get (:index/by-file updated) new-path))))))))

(deftest graph-update-file-path
  (let [old-path "/old/path.clj"
        new-path "/new/path.clj"
        from-id (java.util.UUID/randomUUID)
        e (make-edge 'test.ns/foo 'test.ns/bar old-path from-id (java.util.UUID/randomUUID))
        gr (-> (graph/empty-graph)
               (graph/add-file! [e]))]
    (is (contains? (:by-file gr) old-path))
    (let [updated (graph/update-file-path! gr old-path new-path)]
      (is (not (contains? (:by-file updated) old-path)))
      (is (contains? (:by-file updated) new-path))
      (is (= new-path (:edge/file (first (filter #(= from-id (:edge/from-id %)) (:edges updated)))))))))

(deftest symbol-index-update-file-path-noop-for-unknown
  (let [idx (si/empty-index)
        updated (si/update-file-path! idx "/nonexistent.clj" "/new.clj")]
    (is (= idx updated))))

(deftest graph-update-file-path-noop-for-unknown
  (let [gr (graph/empty-graph)
        updated (graph/update-file-path! gr "/nonexistent.clj" "/new.clj")]
    (is (= gr updated))))

;; ---------------------------------------------------------------------------
;; file-exists?
;; ---------------------------------------------------------------------------

(deftest file-exists-returns-true-for-existing
  (let [d (temp-dir)
        f (io/file d "exists.clj")]
    (spit f "test")
    (is (#'sut/file-exists? (.getPath f)))
    (io/delete-file f true)
    (io/delete-file d true)))

(deftest file-exists-returns-false-for-missing
  (is (not (#'sut/file-exists? "/nonexistent/path.clj"))))

;; ---------------------------------------------------------------------------
;; parse-and-reindex integration: reindex-all! handles Qdrant failure gracefully
;; ---------------------------------------------------------------------------

(deftest reindex-all-handles-qdrant-failure
  (let [cache-dir "/tmp/mcp-test-watcher-cache-reindex"
        cfg (assoc config/defaults
                   :qdrant/port 19999
                   :index/root-path (str fixtures-dir "/multi_ns")
                   :index/exclude (config/compile-exclude-patterns (:index/exclude config/defaults))
                   :embedding/cache-dir cache-dir)
        raw (parser/parse-project (str fixtures-dir "/multi_ns") [])
        enriched (parser/enrich-project-chunks raw)
        all-chunks (:chunks enriched)
        _ (run! (fn [c]
                  (embeddings/cache-embedding! cfg (:chunk/hash c) [(double (hash (:chunk/name c)))]))
                all-chunks)
        result (sut/reindex-all! cfg)]
    (is (map? result))
    (is (contains? result :index))
    (is (contains? result :graph))
    (is (pos? (count (get-in result [:index :index/by-qname]))))
    (doseq [^java.io.File f (.listFiles (io/file cache-dir))]
      (io/delete-file f true))
    (io/delete-file (io/file cache-dir) true)))

;; ---------------------------------------------------------------------------
;; Edge cases: rename with different content
;; ---------------------------------------------------------------------------

(deftest process-rename-different-content-reindexes
  (let [^java.io.File d (temp-dir)
        f-src (io/file d "old.clj")
        f-dst (io/file d "new.clj")]
    (spit f-src "(ns old-ns) (defn foo [x] x)")
    (spit f-dst "(ns new-ns) (defn bar [x] (* x 2))")
    (let [cfg (assoc config/defaults :qdrant/port 19999
                     :embedding/cache-dir "/tmp/mcp-test-watcher-cache-rename-diff")
          idx (si/empty-index)
          gr (graph/empty-graph)
          result (sut/process-rename cfg idx gr (.getPath f-src) (.getPath f-dst))]
      (is (map? result))
      (is (contains? result :index))
      (is (contains? result :graph)))
    (io/delete-file f-src true)
    (io/delete-file f-dst true)
    (io/delete-file d true)))

;; ---------------------------------------------------------------------------
;; Edge cases: process-modify on file with syntax error
;; ---------------------------------------------------------------------------

(deftest process-modify-syntax-error-cleanup
  (let [^java.io.File d (temp-dir)
        f (io/file d "syntax_error.clj")
        fixtures-dir "test-resources/fixtures"]
    (io/copy (io/file fixtures-dir "syntax_error.clj") f)
    (let [cfg (assoc config/defaults :qdrant/port 19999
                     :index/root-path (.getPath d)
                     :embedding/cache-dir "/tmp/mcp-test-watcher-cache-syntax")
          sym (make-symbol 'old-func (.getPath f))
          idx (si/add-file! (si/empty-index) [sym])
          gr (graph/empty-graph)
          result (sut/process-modify cfg idx gr (.getPath f))]
      (is (map? result))
      (is (empty? (get-in result [:index :index/by-qname])))
      (is (empty? (get-in result [:graph :by-file]))))
    (io/delete-file f true)
    (io/delete-file d true)))

;; ---------------------------------------------------------------------------
;; Edge cases: process-delete removes from all indexes
;; ---------------------------------------------------------------------------

(deftest process-delete-removes-from-graph-by-file
  (let [file-path "/tmp/test-delete-graph.clj"
        cfg (assoc config/defaults :qdrant/port 19999)
        from-id (java.util.UUID/randomUUID)
        sym (make-symbol 'test-func file-path)
        idx (si/add-file! (si/empty-index) [sym])
        e (make-edge 'test.ns/test-func 'test.ns/other file-path from-id (java.util.UUID/randomUUID))
        gr (graph/add-file! (graph/empty-graph) [e])
        result (sut/process-delete cfg idx gr file-path)]
    (is (not (contains? (get-in result [:graph :by-file]) file-path)))
    (is (empty? (get-in result [:graph :edges])))))

;; ---------------------------------------------------------------------------
;; Edge cases: coalesce-events edge cases
;; ---------------------------------------------------------------------------

(deftest coalesce-values-stale-events-skipped
  (let [old-time (- (System/currentTimeMillis) 5000)
        queue {:pending [(assoc (#'sut/make-pending :ENTRY_CREATE "/tmp/old.clj")
                               :timestamp old-time)]
               :last-event old-time}
        result (#'sut/coalesce-events queue)]
    (is (empty? (:actions result)))
    (is (= 1 (count (:pending result))))))

;; ---------------------------------------------------------------------------
;; restore-state! stub tests (unit-test level)
;; ---------------------------------------------------------------------------

(deftest restore-state-recreate-full-reindex
  (let [cfg (assoc config/defaults :qdrant/recreate? true
                   :index/root-path "test-resources/fixtures/multi_ns"
                   :index/exclude (config/compile-exclude-patterns (:index/exclude config/defaults))
                   :qdrant/port 19999
                   :embedding/cache-dir "/tmp/mcp-test-restore-state-recreate")]
    (let [result (sut/restore-state! cfg)]
      (is (map? result))
      (is (contains? result :index))
      (is (contains? result :graph)))))

(deftest restore-state-fallback-on-missing-collection
  (let [cfg (assoc config/defaults :qdrant/recreate? false
                   :index/root-path "test-resources/fixtures/multi_ns"
                   :index/exclude (config/compile-exclude-patterns (:index/exclude config/defaults))
                   :qdrant/port 19999
                   :embedding/cache-dir "/tmp/mcp-test-restore-state-fallback")]
    (let [result (sut/restore-state! cfg)]
      (is (map? result))
      (is (contains? result :index))
      (is (contains? result :graph)))))

;; ---------------------------------------------------------------------------
;; sha-256-file edge cases
;; ---------------------------------------------------------------------------

(deftest sha-256-file-zero-length-file
  (let [d (temp-dir)
        f (io/file d "empty.clj")]
    (spit f "")
    (let [hash (#'sut/sha-256-file (.getPath f))]
      (is (string? hash))
      (is (= 64 (count hash))))
    (io/delete-file f true)
    (io/delete-file d true)))

(deftest sha-256-file-binary-content
  (let [d (temp-dir)
        f (io/file d "binary.clj")]
    (.createNewFile f)
    (let [^java.io.FileOutputStream os (java.io.FileOutputStream. f)]
      (.write os (byte-array [0 0xFF 0x00 0x7F]))
      (.close os))
    (let [hash (#'sut/sha-256-file (.getPath f))]
      (is (string? hash))
      (is (= 64 (count hash))))
    (io/delete-file f true)
    (io/delete-file d true)))

;; ---------------------------------------------------------------------------
;; should-watch? edge cases
;; ---------------------------------------------------------------------------

(deftest should-watch-case-insensitive-extensions
  (is (#'sut/should-watch? "/project/SRC.CLJ" [".clj"] []))
  (is (#'sut/should-watch? "/project/Src.CLJC" [".cljc"] []))
  (is (#'sut/should-watch? "/project/source.ClJs" [".cljs"] [])))

(deftest should-watch-multiple-exclude-patterns
  (is (nil? (#'sut/should-watch? "/project/target/classes/core.clj" [".clj"]
                                   [#"target" #"classes"])))
  (is (nil? (#'sut/should-watch? "/project/.git/objects/core.clj" [".clj"]
                                   [#"target" #"\.git"])))
  (is (some? (#'sut/should-watch? "/project/src/core.clj" [".clj"]
                                   [#"target" #"\.git"]))))