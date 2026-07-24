(ns mcp.symbol-index
  (:require [clojure.string :as s]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; SymbolIndex data structure
;; ---------------------------------------------------------------------------
;;
;; An index is a map with the following structure:
;;   {:index/by-qname  {'ns/sym  SymbolRecord ...}   ;; qualified name -> record
;;    :index/by-simple {'sym     [SymbolRecord ...]}   ;; simple name -> records
;;    :index/by-file   {"/path"  [SymbolRecord ...]}   ;; file path -> records
;;    :index/by-type   {:fn     [SymbolRecord ...]     ;; type -> records
;;                      :macro  [...]
;;                      :protocol [...]
;;                      :record [...]
;;                      :var    [...]}
;;    :index/by-protocol {'ProtocolName [SymbolRecord ...]}  ;; protocol-name -> methods
;;    :index/by-record   {'RecordName   [SymbolRecord ...]}  ;; record-name -> methods
;;    :index/namespaces {'ns.name NamespaceRecord ...}}  ;; ns-name -> NamespaceRecord
;; ---------------------------------------------------------------------------

(defn empty-index
  []
  {:index/by-qname    {}
   :index/by-simple   {}
   :index/by-file     {}
   :index/by-type     {:fn [] :macro [] :protocol [] :record [] :var []}
   :index/by-protocol {}
   :index/by-record   {}
   :index/namespaces  {}})

;; ---------------------------------------------------------------------------
;; NamespaceRecord extraction from ns chunks (text-based, no rewrite-clj dep)
;; ---------------------------------------------------------------------------

(defn- parse-ns-require-form
  "Extract require/use/import info from an ns form's require-like clause.
  Returns a vector like [:require [clojure.string :as str] ...] or nil."
  [clause-str]
  (let [trimmed (s/trim clause-str)]
    (when (and (.startsWith trimmed "(") (.endsWith trimmed ")"))
      (let [inner (subs trimmed 1 (dec (count trimmed)))
            parts (s/split inner #"\s+" 2)]
        (when (>= (count parts) 2)
          (let [directive (first parts)]
            (when (#{"require" "use" "import" ":require" ":use" ":import"} directive)
              {:directive (keyword (s/replace directive #"^:" ""))
               :specs (s/trim (second parts))})))))))

(defn- extract-ns-record
  [chunk]
  (when (= :ns (:chunk/type chunk))
    (let [source (:chunk/source chunk)
          ns-name (:chunk/name chunk)]
      (try
        (let [lines (s/split-lines source)
              body-lines (rest lines)
              requires (into []
                             (keep (fn [line]
                                     (let [t (s/trim line)]
                                       (when (or (.startsWith t "(:require")
                                                  (.startsWith t "(:use")
                                                  (.startsWith t "(:import")
                                                  (.startsWith t "[")
                                                  (.startsWith t "#{"))
                                         (parse-ns-require-form line)))))
                             body-lines)]
          {:ns/name      (symbol ns-name)
           :ns/file      (:chunk/file chunk)
           :ns/requires  requires
           :ns/imports   []
           :ns/provides  #{}})
        (catch Exception _ nil)))))

(defn build-index
  ([symbols]
   (build-index symbols nil))
  ([symbols chunks]
   (let [by-qname (into {} (map (juxt :sym/name identity)) symbols)
         by-simple (reduce (fn [acc sym]
                             (update acc (:sym/simple sym)
                                     (fnil conj []) sym))
                           {} symbols)
         by-file (reduce (fn [acc sym]
                           (update acc (:sym/file sym)
                                   (fnil conj []) sym))
                         {} symbols)
         by-type (reduce (fn [acc sym]
                           (update acc (:sym/type sym)
                                   (fnil conj []) sym))
                         {:fn [] :macro [] :protocol [] :record [] :var []}
                         symbols)
         ns-records (when chunks
                      (reduce (fn [acc chunk]
                                (if-let [ns-rec (extract-ns-record chunk)]
                                  (assoc acc (:ns/name ns-rec) ns-rec)
                                  acc))
                              {} chunks))]
     {:index/by-qname   by-qname
      :index/by-simple  by-simple
      :index/by-file    by-file
      :index/by-type    by-type
      :index/by-protocol (reduce (fn [acc sym]
                                   (if-let [pname (:sym/protocol sym)]
                                     (update acc pname (fnil conj []) sym)
                                     acc))
                                 {} symbols)
      :index/by-record   (reduce (fn [acc sym]
                                   (if-let [rname (:sym/record sym)]
                                     (update acc rname (fnil conj []) sym)
                                     acc))
                                 {} symbols)
      :index/namespaces ns-records})))

;; ---------------------------------------------------------------------------
;; Lookups
;; ---------------------------------------------------------------------------

(defn find-symbol
  [index name]
  (let [sym (if (instance? clojure.lang.Named name) name (symbol name))]
    (if (qualified-symbol? sym)
      (when-let [rec (get (:index/by-qname index) sym)]
        [rec])
      (get (:index/by-simple index) sym))))

(defn find-namespace
  [index ns-name]
  (get (:index/namespaces index) (symbol ns-name)))

(defn find-by-type
  [index type]
  (get (:index/by-type index) type []))

(defn find-by-protocol
  [index protocol-name]
  (get (:index/by-protocol index) (symbol (name protocol-name)) []))

(defn find-by-record
  [index record-name]
  (get (:index/by-record index) (symbol (name record-name)) []))

;; ---------------------------------------------------------------------------
;; Incremental update helpers
;; ---------------------------------------------------------------------------

(defn- remove-from-inverted-index
  [m removed-syms]
  (reduce (fn [acc sym]
            (let [k (or (:sym/protocol sym) (:sym/record sym))]
              (if k
                (let [remaining (remove (fn [r] (= (:sym/name r) (:sym/name sym)))
                                        (get acc k []))]
                  (if (empty? remaining)
                    (dissoc acc k)
                    (assoc acc k remaining)))
                acc)))
          m removed-syms))

(defn remove-file!
  [index file-path]
  (let [removed (get (:index/by-file index) file-path [])]
    (if (empty? removed)
      index
      (let [removed-qnames (set (map :sym/name removed))
            removed-simples (set (map :sym/simple removed))
            removed-types (frequencies (map :sym/type removed))]
        (-> index
            (update :index/by-qname
                    (fn [m] (apply dissoc m removed-qnames)))
            (update :index/by-simple
                    (fn [m]
                      (reduce (fn [acc s]
                                (let [remaining (remove (fn [r] (contains? removed-qnames (:sym/name r)))
                                                        (get m s []))]
                                  (if (empty? remaining)
                                    (dissoc acc s)
                                    (assoc acc s remaining))))
                              m removed-simples)))
            (update :index/by-file dissoc file-path)
            (update :index/by-type
                    (fn [m]
                      (reduce-kv (fn [acc t cnt]
                                   (let [remaining (drop-last cnt (get m t []))]
                                     (assoc acc t remaining)))
                                 m removed-types)))
            (update :index/by-protocol remove-from-inverted-index removed)
            (update :index/by-record remove-from-inverted-index removed))))))

(defn add-file!
  [index symbols]
  (if (empty? symbols)
    index
    (let [file-path (:sym/file (first symbols))
          without-old (if file-path (remove-file! index file-path) index)]
      (-> without-old
          (update :index/by-qname
                  (fn [m] (into m (map (juxt :sym/name identity)) symbols)))
          (update :index/by-simple
                  (fn [m]
                    (reduce (fn [acc sym]
                              (update acc (:sym/simple sym)
                                      (fnil conj []) sym))
                            m symbols)))
          (update :index/by-file
                  (fn [m] (assoc m file-path symbols)))
          (update :index/by-type
                  (fn [m]
                    (reduce (fn [acc sym]
                              (update acc (:sym/type sym)
                                      (fnil conj []) sym))
                            m symbols)))
          (update :index/by-protocol
                  (fn [m]
                    (reduce (fn [acc sym]
                              (if-let [pname (:sym/protocol sym)]
                                (update acc pname (fnil conj []) sym)
                                acc))
                            m symbols)))
          (update :index/by-record
                  (fn [m]
                    (reduce (fn [acc sym]
                              (if-let [rname (:sym/record sym)]
                                (update acc rname (fnil conj []) sym)
                                acc))
                            m symbols)))))))