(ns mcp.search-units
  "Search units: what semantic search embeds and returns. One unit per
   Definition of the precise index (`mcp.code-index`) and one per top-level
   entry of an EDN map. Reads source files, never Qdrant or Ollama."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [rewrite-clj.zip :as z])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(set! *warn-on-reflection* true)

;; Search unit shape:
;;
;; {:id   #uuid "..."               ; stable for the same file, kind, name and row
;;  :filename "/abs/src/shop/orders.clj"
;;  :row  5
;;  :kind "defn"                    ; a Definition kind, or "edn"
;;  :name "shop.orders/order-total" ; qualified name, or the EDN key as written
;;  :doc  "First line.\n More."     ; the whole docstring, as in a Definition;
;;                                  ;  absent when there is none
;;  :text "..."                     ; the embedding text
;;  :hash "sha-256 of :text"}       ; embedding cache key
;;
;; Units read back from Qdrant have a string `:id` and no `:hash`.

(s/def ::search-unit
  (s/keys :req-un [::id ::filename ::row ::kind ::name ::text]
          :opt-un [::doc ::hash]))

;; SearchUnitParts: what `unit` builds a Search unit from; :unit-name becomes
;; :name, and the non-blank :text-parts are joined into :text.
;;
;; {:filename "/abs/src/shop/orders.clj" :row 5 :kind "defn"
;;  :unit-name "shop.orders/order-total"
;;  :doc "..."                      ; absent or nil when there is none
;;  :text-parts ["..." nil "..."]}

(s/def ::search-unit-parts
  (s/keys :req-un [::filename ::row ::kind ::unit-name ::text-parts] :opt-un [::doc]))

;; ZLoc: a rewrite-clj zipper location, as `rewrite-clj.zip` returns it.

(s/def ::zloc some?)

(def ^:private max-source-chars
  "Source beyond this many characters is not embedded.

  Long"
  2000)

(def ^:private generated-record-constructor #"^(->|map->)")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sha-256
  "The SHA-256 of `text` as a hex string.

  String -> String"
  [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) digest))))

(defn- unit-id
  "The id of a Search unit, stable for the same file, kind, name and row.

  String -> String -> String -> Long -> UUID"
  [file-path kind unit-name row]
  (let [key-str (st/join "|" [file-path kind unit-name row])]
    (UUID/nameUUIDFromBytes (.getBytes ^String key-str StandardCharsets/UTF_8))))

(defn- truncate
  "The start of `text`, at most `max-source-chars` characters.

  String -> String"
  [text]
  (cond-> text
    (> (count text) max-source-chars) (subs 0 max-source-chars)))

(defn- unit
  "The Search unit with an embedding text joined from the non-blank
   `text-parts`.

  SearchUnitParts -> SearchUnit"
  [{:keys [filename row kind unit-name doc text-parts]}]
  (let [text (->> text-parts (remove st/blank?) (st/join "\n"))]
    (cond-> {:id (unit-id filename kind unit-name row)
             :filename filename
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
  "The source of the form spanning `row:col` to `end-row:end-col` in
   `lines`; nil when the range lies outside them.

  [String] -> FormRange -> Maybe String"
  [lines form-range]
  (some->> (code-index/form-lines lines form-range)
           (st/join "\n")))

(defn definition-unit
  "The Search unit of `definition`; `lines-by-file` maps absolute file
   paths to their lines. Embedding text: `ns/name`, arglists, docstring and
   the source truncated to about 2000 characters.

  {String [String]} -> Definition -> SearchUnit"
  [lines-by-file {:keys [filename row kind arglists doc] :as definition}]
  (let [qualified-name (code-index/qualified-name definition)]
    (unit {:filename filename
           :row row
           :kind kind
           :unit-name qualified-name
           :doc doc
           :text-parts [qualified-name
                        (st/join " " arglists)
                        doc
                        (some-> (form-source (get lines-by-file filename) definition)
                                truncate)]})))

(defn- generated-constructor?
  "True for `->Record` and `map->Record`, which share their record's form.

  Definition -> Boolean"
  [{:keys [kind] def-name :name}]
  (and (= "defrecord" kind)
       (symbol? def-name)
       (re-find generated-record-constructor (name def-name))))

(defn- definition-units
  "The Search units of the Definitions in `index`, reading their files.

  CodeIndex -> IO [SearchUnit]"
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
  "Reads a zipper on the first form of `file-path` when it is a map, else
   nil.

  String -> IO (Maybe ZLoc)"
  [^String file-path]
  (let [root (z/of-file file-path {:track-position? true})]
    (when (and root (= :map (z/tag root)))
      root)))

(defn- edn-entry-unit
  "The Search unit of one top-level entry of an EDN map.

  String -> ZLoc -> ZLoc -> IO SearchUnit"
  [file-path key-loc value-loc]
  (let [[row col] (z/position key-loc)
        [_value-start [end-row end-col]] (z/position-span value-loc)
        key-str (z/string key-loc)
        source (form-source (code-index/file-lines file-path)
                            {:row row :col col :end-row end-row :end-col end-col})]
    (unit {:filename file-path
           :row row
           :kind "edn"
           :unit-name key-str
           :text-parts [key-str (truncate source)]})))

(defn- edn-file-units
  "The Search units of the top-level entries of the EDN map in
   `file-path`; nil when the file is not a readable map.

  String -> IO (Maybe [SearchUnit])"
  [^String file-path]
  (when-let [map-loc (try (top-level-map file-path)
                          (catch Exception _ nil))]
    (->> (iterate z/right (z/down map-loc))
         (take-while some?)
         (partition 2)
         (map (fn [[key-loc value-loc]] (edn-entry-unit file-path key-loc value-loc))))))

(defn- edn-units
  "The Search units of every EDN file in `index`.

  CodeIndex -> IO [SearchUnit]"
  [index]
  (->> (code-index/files index)
       (filter #(st/ends-with? (st/lower-case %) ".edn"))
       (mapcat edn-file-units)))

;; ---------------------------------------------------------------------------
;; API
;; ---------------------------------------------------------------------------

(defn search-units
  "All Search units of the project in `index`: every Definition and every
   top-level entry of an EDN map, in file order.

  CodeIndex -> IO [SearchUnit]"
  [index]
  (->> (concat (definition-units index) (edn-units index))
       (sort-by (juxt :filename :row))))

(defn file-search-units
  "The Search units of the files `file-paths` (absolute) in `index`.

  CodeIndex -> [String] -> IO [SearchUnit]"
  [index file-paths]
  (search-units (code-index/select-files index file-paths)))
