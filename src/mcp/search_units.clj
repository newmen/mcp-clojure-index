(ns mcp.search-units
  "Search units: what semantic search embeds and returns. One unit per
   Definition of the precise index (`mcp.code-index`) and one per top-level
   entry of an EDN map. Pure: reads source files, never Qdrant or Ollama."
  (:require [clojure.string :as s]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [rewrite-clj.zip :as z])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(set! *warn-on-reflection* true)

;; Search unit shape:
;;
;; {:id   #uuid "..."               ; stable for the same file, kind, name and row
;;  :file "/abs/src/shop/orders.clj"
;;  :row  5
;;  :kind "defn"                    ; a Definition kind, or "edn"
;;  :name "shop.orders/order-total" ; qualified name, or the EDN key as written
;;  :doc  "First docstring line."   ; absent when there is none
;;  :text "..."                     ; the embedding text
;;  :hash "sha-256 of :text"}       ; embedding cache key

(def ^:private max-source-chars
  "Source beyond this many characters is not embedded."
  2000)

(def ^:private generated-record-constructor #"^(->|map->)")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sha-256
  [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) digest))))

(defn- unit-id
  [file-path kind unit-name row]
  (let [key-str (s/join "|" [file-path kind unit-name row])]
    (UUID/nameUUIDFromBytes (.getBytes ^String key-str StandardCharsets/UTF_8))))

(defn- truncate
  [text]
  (cond-> text
    (> (count text) max-source-chars) (subs 0 max-source-chars)))

(defn- unit
  [{:keys [file row kind unit-name doc text-parts]}]
  (let [text (->> text-parts (remove s/blank?) (s/join "\n"))]
    (cond-> {:id (unit-id file kind unit-name row)
             :file file
             :row row
             :kind kind
             :name unit-name
             :text text
             :hash (sha-256 text)}
      doc (assoc :doc doc))))

;; ---------------------------------------------------------------------------
;; Definitions
;; ---------------------------------------------------------------------------

(defn- form-source
  "Source of the form spanning `row:col` to `end-row:end-col` in `lines`."
  [lines form-range]
  (some->> (code-index/form-lines lines form-range)
           (s/join "\n")))

(defn definition-unit
  "Search unit of `definition`; `lines-by-file` maps absolute file paths
   to their lines. Embedding text: `ns/name`, arglists, docstring and the
   source truncated to about 2000 characters."
  [lines-by-file {:keys [filename row kind arglists doc] :as definition}]
  (let [qualified-name (code-index/qualified-name definition)]
    (unit {:file filename
           :row row
           :kind kind
           :unit-name qualified-name
           :doc (output/first-line doc)
           :text-parts [qualified-name
                        (s/join " " arglists)
                        doc
                        (some-> (form-source (get lines-by-file filename) definition)
                                truncate)]})))

(defn- generated-constructor?
  "True for `->Record` and `map->Record`, which share their record's form."
  [{:keys [kind] def-name :name}]
  (and (= "defrecord" kind)
       (symbol? def-name)
       (re-find generated-record-constructor (name def-name))))

(defn- definition-units
  [index]
  (let [definitions (remove generated-constructor? (code-index/definitions index))
        lines-by-file (into {}
                            (map (juxt identity code-index/file-lines))
                            (distinct (map :filename definitions)))]
    (map (partial definition-unit lines-by-file) definitions)))

;; ---------------------------------------------------------------------------
;; EDN map entries
;; ---------------------------------------------------------------------------

(defn- top-level-map
  "Zipper on the first form of `file-path` when it is a map, else nil."
  [^String file-path]
  (let [root (z/of-file file-path {:track-position? true})]
    (when (and root (= :map (z/tag root)))
      root)))

(defn- edn-entry-unit
  [file-path key-loc value-loc]
  (let [[row col] (z/position key-loc)
        [_value-start [end-row end-col]] (z/position-span value-loc)
        key-str (z/string key-loc)
        source (form-source (code-index/file-lines file-path)
                            {:row row :col col :end-row end-row :end-col end-col})]
    (unit {:file file-path
           :row row
           :kind "edn"
           :unit-name key-str
           :text-parts [key-str (truncate source)]})))

(defn- edn-file-units
  [^String file-path]
  (when-let [map-loc (try (top-level-map file-path)
                          (catch Exception _ nil))]
    (->> (iterate z/right (z/down map-loc))
         (take-while some?)
         (partition 2)
         (map (fn [[key-loc value-loc]] (edn-entry-unit file-path key-loc value-loc))))))

(defn- edn-units
  [index]
  (->> (code-index/files index)
       (filter #(s/ends-with? (s/lower-case %) ".edn"))
       (mapcat edn-file-units)))

;; ---------------------------------------------------------------------------
;; API
;; ---------------------------------------------------------------------------

(defn search-units
  "All Search units of the project in `index`: every Definition and every
   top-level entry of an EDN map, in file order."
  [index]
  (->> (concat (definition-units index) (edn-units index))
       (sort-by (juxt :file :row))))

(defn file-search-units
  "Search units of the files `file-paths` (absolute) in `index`."
  [index file-paths]
  (search-units (code-index/select-files index file-paths)))
