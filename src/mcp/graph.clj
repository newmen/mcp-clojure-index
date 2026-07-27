(ns mcp.graph
  (:require [clojure.string :as s]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; GraphResult data structure
;; ---------------------------------------------------------------------------
;;
;; {:edges     [Edge ...]                        ;; all edges
;;  :callers   {qualified-sym [qualified-sym ...]} ;; caller -> callees
;;  :callees   {qualified-sym [qualified-sym ...]} ;; callee -> callers
;;  :by-chunk  {uuid [Edge ...]}                  ;; chunk-id -> edges
;;  :by-file   {"/path" #{uuid ...}}}             ;; file -> chunk-ids
;;
;; Each Edge:
;;   {:edge/from     'ns/sym   ;; caller symbol
;;    :edge/from-id   uuid     ;; caller chunk-id
;;    :edge/to       'ns/sym   ;; callee symbol
;;    :edge/to-id     uuid     ;; callee chunk-id (may be nil if unresolvable)
;;    :edge/type     :call     ;; :call | :require
;;    :edge/file     "/path"
;;    :edge/line     number}
;; ---------------------------------------------------------------------------

(defn empty-graph
  []
  {:edges    []
   :callers  {}
   :callees  {}
   :by-chunk {}
   :by-file  {}})

;; ---------------------------------------------------------------------------
;; Symbol resolution helpers
;; ---------------------------------------------------------------------------

(defn- resolve-require-alias
  "Resolve an alias like 'a' from the calling namespace's :require to the full namespace symbol.
   Returns nil if ns-name is nil or no matching alias is found."
  [alias-str ^String ns-name index]
  (when (and ns-name (seq ns-name))
    (let [ns-sym (symbol ns-name)
          ns-rec (get (:index/namespaces index) ns-sym)]
      (when ns-rec
        (some (fn [req]
                  (let [specs (:specs req)]
                    (when (s/includes? specs (str ":as " alias-str))
                      (let [parts (s/split specs #"\s+")]
                        (when (pos? (count parts))
                          (symbol (first parts)))))))
                (:ns/requires ns-rec))))))

(defn- resolve-symbol
  [sym ^String ns-name index]
  (let [ns-sym (when ns-name (symbol ns-name))]
    (if-let [rec (get (:index/by-qname index) sym)]
      (:sym/name rec)
      (if-let [ns-part (namespace sym)]
        (let [simple (name sym)
              records (get (:index/by-simple index) (symbol simple))]
          (when (seq records)
            (if-let [actual-ns (resolve-require-alias ns-part ns-name index)]
              (let [preferred (filter #(= actual-ns (:sym/ns %)) records)]
                (:sym/name (first (or (seq preferred) records))))
              (:sym/name (first records)))))
        (let [records (get (:index/by-simple index) sym)]
          (when (seq records)
            (let [same-ns (filter #(= ns-sym (:sym/ns %)) records)]
              (if (seq same-ns)
                (:sym/name (first same-ns))
                (:sym/name (first records))))))))))

(defn- resolve-to-id
  "Look up a chunk-id for a qualified symbol via the index."
  [qname index]
  (when-let [rec (get (:index/by-qname index) qname)]
    (:sym/chunk-id rec)))

;; ---------------------------------------------------------------------------
;; Edge deduplication
;; ---------------------------------------------------------------------------

(defn- edge-key
  [e]
  [(:edge/from e) (:edge/to e) (:edge/type e)])

(defn- deduplicate-edges
  [edges]
  (->> edges
       (map (fn [e] [(edge-key e) e]))
       (into {})
       (vals)))

;; ---------------------------------------------------------------------------
;; Build edges from a single chunk
;; ---------------------------------------------------------------------------

(def ^:private special-operators
  #{'def 'defn 'defn- 'defmacro 'defprotocol 'defrecord 'deftype
    'defonce 'ns 'fn 'let 'letfn 'loop 'if 'do 'case 'cond
    'recur 'throw 'try 'catch 'finally 'monitor-enter 'monitor-exit
    'new '. 'quote 'var 'import 'set! '-> '->> 'some-> 'some->>
    'as-> 'cond-> 'cond->> 'when 'when-not 'when-let 'if-let
    'if-some 'when-some 'for 'doseq 'dotimes 'while 'binding
    'with-open 'with-local-vars 'locking 'time 'comment 'println
    'mapv 'map 'filter 'reduce 'assoc 'get 'str})

(defn chunk->edges
  "Build call edges from a single chunk. Returns a vector of Edge maps.
   Exported for incremental updates via add-file!."
  [chunk index]
  (let [ftype (:chunk/type chunk)
        cname (:chunk/name chunk)
        ns-name (:chunk/ns chunk)
        qname (symbol (str ns-name "/" cname))
        chunk-id (:chunk/id chunk)
        symbols (:chunk/symbols chunk)]
    (if (or (nil? cname) (= :ns ftype))
      []
      (let [callee-edges
            (into []
                  (keep (fn [ref-sym]
                          (when (not (contains? special-operators ref-sym))
                            (when-let [resolved (resolve-symbol ref-sym ns-name index)]
                              (when (not= qname resolved)
                                (let [to-id (resolve-to-id resolved index)]
                                  {:edge/from    qname
                                   :edge/from-id chunk-id
                                   :edge/to      resolved
                                   :edge/to-id   to-id
                                   :edge/type    :call
                                   :edge/file    (:chunk/file chunk)
                                   :edge/line    (:chunk/start-line chunk)})))))
                        symbols))]
        callee-edges))))

;; ---------------------------------------------------------------------------
;; Build full graph
;; ---------------------------------------------------------------------------

(defn build-graph
  [chunks index]
  (let [all-edges
        (into []
              (mapcat #(chunk->edges % index))
              chunks)
        deduped (deduplicate-edges all-edges)
        callers (reduce (fn [acc e]
                          (update acc (:edge/to e)
                                  (fnil conj []) (:edge/from e)))
                        {} deduped)
        callees (reduce (fn [acc e]
                          (update acc (:edge/from e)
                                  (fnil conj []) (:edge/to e)))
                        {} deduped)
        by-chunk (reduce (fn [acc e]
                           (-> acc
                               (update (:edge/from-id e)
                                       (fnil conj []) e)))
                         {} deduped)
        by-file (reduce (fn [acc e]
                          (update acc (:edge/file e)
                                  (fnil conj #{}) (:edge/from-id e)))
                        {} deduped)]
    {:edges    deduped
     :callers  callers
     :callees  callees
     :by-chunk by-chunk
     :by-file  by-file}))

;; ---------------------------------------------------------------------------
;; Lookups
;; ---------------------------------------------------------------------------

(defn find-callers
  [graph symbol]
  (vec (sort (get (:callers graph) symbol []))))

(defn find-callees
  [graph symbol]
  (vec (sort (get (:callees graph) symbol []))))

(defn find-edges-by-chunk
  [graph chunk-id]
  (get (:by-chunk graph) chunk-id []))

;; ---------------------------------------------------------------------------
;; Incremental update helpers
;; ---------------------------------------------------------------------------

(defn remove-file!
  [graph file-path]
  (let [removed-ids (get (:by-file graph) file-path #{})]
    (if (empty? removed-ids)
      graph
      (let [removed-edges (into []
                                 (filter #(contains? removed-ids (:edge/from-id %)))
                                 (:edges graph))
            removed-keys (set (map edge-key removed-edges))
            remaining-edges (remove #(contains? removed-keys (edge-key %))
                                    (:edges graph))]
        {:edges    remaining-edges
         :callers  (reduce (fn [acc e]
                             (let [callee (:edge/to e)
                                   caller (:edge/from e)]
                               (if-let [remaining (seq (remove #(= caller %)
                                                               (get acc callee [])))]
                                 (assoc acc callee remaining)
                                 (dissoc acc callee))))
                           (:callers graph)
                           removed-edges)
         :callees  (reduce (fn [acc e]
                             (let [caller (:edge/from e)
                                   callee (:edge/to e)]
                               (if-let [remaining (seq (remove #(= callee %)
                                                               (get acc caller [])))]
                                 (assoc acc caller remaining)
                                 (dissoc acc caller))))
                           (:callees graph)
                           removed-edges)
         :by-chunk (reduce (fn [acc e]
                             (let [cid (:edge/from-id e)]
                               (if-let [remaining (seq (remove #(= (edge-key e) (edge-key %))
                                                                (get acc cid [])))]
                                 (assoc acc cid remaining)
                                 (dissoc acc cid))))
                           (:by-chunk graph)
                           removed-edges)
         :by-file  (dissoc (:by-file graph) file-path)}))))

(defn add-file!
  [graph edges]
  (if (empty? edges)
    graph
    (let [file-path (:edge/file (first edges))
          without-old (if file-path
                        (remove-file! graph file-path)
                        graph)
          deduped (deduplicate-edges edges)]
      (-> without-old
          (update :edges into deduped)
          (update :callers
                  (fn [m]
                    (reduce (fn [acc e]
                              (update acc (:edge/to e)
                                      (fnil conj []) (:edge/from e)))
                            m deduped)))
          (update :callees
                  (fn [m]
                    (reduce (fn [acc e]
                              (update acc (:edge/from e)
                                      (fnil conj []) (:edge/to e)))
                            m deduped)))
          (update :by-chunk
                  (fn [m]
                    (reduce (fn [acc e]
                              (update acc (:edge/from-id e)
                                      (fnil conj []) e))
                            m deduped)))
          (update :by-file
                  (fn [m]
                    (reduce (fn [acc e]
                              (update acc (:edge/file e)
                                      (fnil conj #{}) (:edge/from-id e)))
                            m deduped)))))))

;; ---------------------------------------------------------------------------
;; Update file path in all graph entries (rename without content change)
;; ---------------------------------------------------------------------------

(defn update-file-path!
  [graph old-path new-path]
  (let [removed-ids (get (:by-file graph) old-path #{})]
    (if (empty? removed-ids)
      graph
      (let [removed-edges (filter #(contains? removed-ids (:edge/from-id %)) (:edges graph))
            updated-edges (mapv (fn [e] (assoc e :edge/file new-path)) removed-edges)]
        (-> (remove-file! graph old-path)
            (add-file! updated-edges))))))