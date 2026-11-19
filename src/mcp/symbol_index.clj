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
;;    :index/namespaces {'ns.name NamespaceRecord ...}}  ;; ns-name -> NamespaceRecord
;; ---------------------------------------------------------------------------

(defn empty-index
  []
  {:index/by-qname    {}
   :index/by-simple   {}
   :index/by-file     {}
   :index/namespaces  {}})

;; ---------------------------------------------------------------------------
;; NamespaceRecord extraction from ns chunks (text-based, no rewrite-clj dep)
;; ---------------------------------------------------------------------------

(defn- parse-ns-require-form
  "Extract require/use/import info from an ns form's require-like clause.
   Returns a vector like [:require [clojure.string :as s] ...] or nil."
  [clause-str]
  (let [trimmed (s/trim clause-str)]
    (when (and (s/starts-with? trimmed "(") (s/ends-with? trimmed ")"))
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
                                       (when (or (s/starts-with? t "(:require")
                                                 (s/starts-with? t "(:use")
                                                 (s/starts-with? t "(:import")
                                                 (s/starts-with? t "[")
                                                 (s/starts-with? t "#{"))
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
   {:index/by-qname   (into {} (map (juxt :sym/name identity)) symbols)
    :index/by-simple  (reduce (fn [acc sym]
                                (update acc (:sym/simple sym)
                                        (fnil conj []) sym))
                              {} symbols)
    :index/by-file    (reduce (fn [acc sym]
                                (update acc (:sym/file sym)
                                        (fnil conj []) sym))
                              {} symbols)
    :index/namespaces (when chunks
                        (reduce (fn [acc chunk]
                                  (if-let [ns-rec (extract-ns-record chunk)]
                                    (assoc acc (:ns/name ns-rec) ns-rec)
                                    acc))
                                {} chunks))}))

;; ---------------------------------------------------------------------------
;; Incremental update helpers
;; ---------------------------------------------------------------------------

(defn remove-file!
  [index file-path]
  (let [removed (get (:index/by-file index) file-path [])]
    (if (empty? removed)
      index
      (let [removed-qnames (set (map :sym/name removed))
            removed-simples (set (map :sym/simple removed))]
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
            (update :index/by-file dissoc file-path))))))

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
                  (fn [m] (assoc m file-path symbols)))))))

;; ---------------------------------------------------------------------------
;; Update file path in all index entries (rename without content change)
;; ---------------------------------------------------------------------------

(defn update-file-path!
  [index old-path new-path]
  (let [symbols (get (:index/by-file index) old-path [])]
    (if (empty? symbols)
      index
      (let [updated (mapv #(assoc % :sym/file new-path) symbols)]
        (-> index
            (remove-file! old-path)
            (add-file! updated))))))