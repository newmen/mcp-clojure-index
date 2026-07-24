(ns mcp.graph-test
  (:require [clojure.test :refer :all]
            [mcp.parser :as parser]
            [mcp.symbol-index :as si]
            [mcp.graph :as sut]))

(def fixtures-dir "test-resources/fixtures")

(defn fixture-path
  [name]
  (str fixtures-dir "/" name))

(defn- parse-and-build-graph
  [fixture-name]
  (let [result (parser/parse-file (fixture-path fixture-name))
        enriched (parser/enrich-chunks-with-ns result)
        symbols (parser/extract-symbols enriched)
        chunks (:result/chunks enriched)
        index (si/build-index symbols chunks)
        graph (sut/build-graph chunks index)]
    {:graph graph :index index :chunks chunks :symbols symbols}))

;; ---------------------------------------------------------------------------
;; empty-graph
;; ---------------------------------------------------------------------------

(deftest empty-graph-has-all-keys
  (let [g (sut/empty-graph)]
    (is (= [] (:edges g)))
    (is (= {} (:callers g)))
    (is (= {} (:callees g)))
    (is (= {} (:by-chunk g)))
    (is (= {} (:by-file g)))))

;; ---------------------------------------------------------------------------
;; build-graph — valid_defs.clj
;; ---------------------------------------------------------------------------

(deftest build-graph-from-valid-defs
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (sequential? (:edges graph)))
    (is (map? (:callers graph)))
    (is (map? (:callees graph)))))

(deftest valid-defs-edges-not-empty
  (let [{:keys [graph chunks]} (parse-and-build-graph "valid_defs.clj")
        non-ns-chunks (remove #(= :ns (:chunk/type %)) chunks)]
    (is (pos? (count (:edges graph)))
        (str "Expected edges, got 0. Non-ns chunks: " (count non-ns-chunks)))))

(deftest valid-defs-edges-have-required-keys
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (doseq [e (:edges graph)]
      (is (contains? e :edge/from))
      (is (contains? e :edge/from-id))
      (is (contains? e :edge/to))
      (is (contains? e :edge/type))
      (is (contains? e :edge/file))
      (is (contains? e :edge/line)))))

(deftest valid-defs-no-duplicate-edges
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")
        edge-set (set (map (fn [e] [(:edge/from e) (:edge/to e)]) (:edges graph)))]
    (is (= (count edge-set) (count (:edges graph))))))

(deftest valid-defs-record-calls-format-name
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (some #(= 'fixtures.valid-defs/format-name (:edge/to %)) (:edges graph)))
    (is (some #(= 'fixtures.valid-defs/User (:edge/from %)) (:edges graph)))))

;; ---------------------------------------------------------------------------
;; find-callers / find-callees
;; ---------------------------------------------------------------------------

(deftest find-callers-for-format-name
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (contains? (:callers graph) 'fixtures.valid-defs/format-name))))

(deftest find-callers-returns-sorted
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")
        callers (sut/find-callers graph 'fixtures.valid-defs/format-name)]
    (is (sequential? callers))
    (is (= callers (sort callers)))))

(deftest find-callees-returns-sorted
  (let [{:keys [graph]} (parse-and-build-graph "graph_project")
        callees (sut/find-callees graph 'graph-project.core/process)]
    (is (sequential? callees))
    (is (= callees (sort callees)))))

(deftest find-callers-returns-empty-for-unknown
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (= [] (sut/find-callers graph 'nonexistent.sym/foo)))))

(deftest find-callees-returns-empty-for-unknown
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (= [] (sut/find-callees graph 'nonexistent.sym/foo)))))

;; ---------------------------------------------------------------------------
;; find-edges-by-chunk
;; ---------------------------------------------------------------------------

(deftest find-edges-by-chunk-id
  (let [{:keys [graph chunks]} (parse-and-build-graph "valid_defs.clj")
        create-user (first (filter #(= "create-user" (:chunk/name %)) chunks))
        chunk-id (:chunk/id create-user)
        edges (sut/find-edges-by-chunk graph chunk-id)]
    (is (vector? edges))
    (is (every? #(= chunk-id (:edge/from-id %)) edges))))

(deftest find-edges-by-chunk-returns-empty-for-unknown
  (let [{:keys [graph]} (parse-and-build-graph "valid_defs.clj")]
    (is (= [] (sut/find-edges-by-chunk graph (java.util.UUID/randomUUID))))))

;; ---------------------------------------------------------------------------
;; Project-level graph (multi-file)
;; ---------------------------------------------------------------------------

(defn- parse-project-and-build-graph
  [project-dir]
  (let [raw (parser/parse-project (str fixtures-dir "/" project-dir) [])
        enriched (parser/enrich-project-chunks raw)
        by-file (group-by :chunk/file (:chunks enriched))
        all-symbols (into []
                          (mapcat (fn [[_file chunks]]
                                    (let [ns-name (:chunk/ns (first chunks))]
                                      (keep #(parser/chunk->symbol-record % ns-name) chunks))))
                          by-file)
        all-chunks (:chunks enriched)
        index (si/build-index all-symbols all-chunks)
        graph (sut/build-graph all-chunks index)]
    {:graph graph :index index :chunks all-chunks}))

(deftest graph-project-has-cross-file-edges
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (is (pos? (count (:edges graph))))
    (is (some #(= 'graph-project.utils/enrich (:edge/to %)) (:edges graph)))
    (is (some #(= 'graph-project.utils/validate (:edge/to %)) (:edges graph)))))

(deftest graph-project-callers-chain
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (is (contains? (:callers graph) 'graph-project.utils/enrich))
    (is (contains? (:callers graph) 'graph-project.utils/validate))
    (is (contains? (:callees graph) 'graph-project.core/process))
    (is (contains? (:callees graph) 'graph-project.utils/transform))))

(deftest graph-project-utils-edges
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (is (some #(= 'graph-project.utils/transform (:edge/from %)) (:edges graph)))
    (is (some #(= 'graph-project.utils/enrich (:edge/to %)) (:edges graph)))))

;; ---------------------------------------------------------------------------
;; multi_ns project
;; ---------------------------------------------------------------------------

(deftest multi-ns-graph-has-edges
  (let [{:keys [graph]} (parse-project-and-build-graph "multi_ns")]
    (is (pos? (count (:edges graph))))
    (is (some #(= 'multi-ns.core/process (:edge/from %)) (:edges graph)))
    (is (some #(= 'multi-ns.utils/enrich (:edge/to %)) (:edges graph)))
    (is (some #(= 'multi-ns.utils/validate (:edge/to %)) (:edges graph)))))

;; ---------------------------------------------------------------------------
;; remove-file! — incremental update
;; ---------------------------------------------------------------------------

(deftest remove-file-removes-edges
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")
        files (keys (:by-file graph))
        file-to-remove (first files)
        removed (sut/remove-file! graph file-to-remove)]
    (is (not (contains? (:by-file removed) file-to-remove)))
    (is (every? #(not= file-to-remove (:edge/file %)) (:edges removed)))))

(deftest remove-file-noop-for-unknown-file
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")
        removed (sut/remove-file! graph "/nonexistent.clj")]
    (is (= graph removed))))

(deftest remove-file-updates-callers
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")
        file-to-remove (first (keys (:by-file graph)))
        removed (sut/remove-file! graph file-to-remove)
        remaining-edges (:edges removed)
        removed-edges (filter #(= (:edge/file %) file-to-remove) (:edges graph))]
    (doseq [e removed-edges]
      (let [callee (:edge/to e)
            caller (:edge/from e)]
        (when (seq (get (:callers graph) callee []))
          (is (not (some #(= caller %) (get (:callers removed) callee [])))
              (str "Caller " caller " should be removed from " callee " callers")))))))

(deftest remove-file-updates-callees
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")
        file-to-remove (first (keys (:by-file graph)))
        removed (sut/remove-file! graph file-to-remove)
        removed-edges (filter #(= (:edge/file %) file-to-remove) (:edges graph))]
    (doseq [e removed-edges]
      (let [caller (:edge/from e)
            callee (:edge/to e)]
        (is (not (some #(= callee %) (get (:callees removed) caller [])))
            (str "Callee " callee " should be removed from " caller " callees"))))))

;; ---------------------------------------------------------------------------
;; add-file! — incremental update
;; ---------------------------------------------------------------------------

(deftest add-file-adds-edges
  (let [{:keys [graph index]} (parse-project-and-build-graph "multi_ns")
        empty-g (sut/empty-graph)
        all-edges (:edges graph)
        added (sut/add-file! empty-g all-edges)]
    (is (seq (:edges added)))
    (is (seq (:callers added)))
    (is (seq (:callees added)))
    (is (seq (:by-chunk added)))
    (is (seq (:by-file added)))))

(deftest add-file-replaces-existing
  (let [{:keys [graph]} (parse-project-and-build-graph "multi_ns")
        file-path (first (keys (:by-file graph)))
        edges-for-file (filter #(= (:edge/file %) file-path) (:edges graph))
        re-added (sut/add-file! graph edges-for-file)]
    (is (= (count (:edges graph)) (count (:edges re-added))))))

(deftest add-file-noop-for-empty
  (let [g (sut/empty-graph)
        added (sut/add-file! g [])]
    (is (= g added))))

;; ---------------------------------------------------------------------------
;; Graph with cross-symbol resolution
;; ---------------------------------------------------------------------------

(deftest graph-edges-mapped-to-chunk-ids
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (doseq [e (:edges graph)]
      (is (some? (:edge/from-id e))
          (str "Missing from-id for edge: " (:edge/from e) " -> " (:edge/to e))))))

(deftest graph-no-self-loops
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (is (not-any? #(= (:edge/from %) (:edge/to %)) (:edges graph))
        "Graph should not contain self-loops")))

(deftest graph-all-edges-have-type-call
  (let [{:keys [graph]} (parse-project-and-build-graph "graph_project")]
    (is (every? #(= :call (:edge/type %)) (:edges graph)))))