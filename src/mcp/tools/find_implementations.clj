(ns mcp.tools.find-implementations
  "`find_implementations`: the Implementations of a protocol (records,
   types, `extend-type`, `extend-protocol`, `reify`) or of a multimethod
   (`defmethod`), as Compact answer lines (ADR-0006).

   clj-kondo's `:protocol-impls` carry the method but not the implementing
   type, so the type is derived: from the enclosing `defrecord`/`deftype`
   Definition, from the source of the enclosing `extend-*` form, or, for
   `reify`, from the enclosing Definition."
  (:require [clojure.string :as s]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]
            [rewrite-clj.zip :as z]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Find the Implementations of a Clojure protocol or multimethod. "
       "Unlike grep, sees every form that implements it: defrecord, deftype, extend-type, "
       "extend-protocol and reify for a protocol, defmethod for a multimethod, also when "
       "the protocol is referred to through an alias; the name resolves like in find_definition. "
       "Answer: one line per Implementation (path:line  ns/type or dispatch value  method), "
       "at most :output/limit lines and :output/max-chars characters. Use get_source for bodies."))

(def ^:private implementable-kinds
  #{"defprotocol" "defmulti"})

(def ^:private extend-forms
  #{'clojure.core/extend-type 'clojure.core/extend-protocol})

(def ^:private record-or-type-forms
  #{'clojure.core/defrecord 'clojure.core/deftype})

(defmethod tool/spec :find_implementations
  [_tool-key]
  {:name "find_implementations"
   :description description
   :inputSchema {:type "object"
                 :properties (merge {:name {:type "string"
                                            :description "Protocol or multimethod name: simple, alias-qualified or fully qualified"}}
                                    tool/paging-properties)
                 :required ["name"]}})

;; ---------------------------------------------------------------------------
;; Positions
;; ---------------------------------------------------------------------------

(defn- contains-start?
  "True when the form range of `entry` contains the start of `inner`."
  [entry inner]
  (and (= (:filename entry) (:filename inner))
       (code-index/contains-position? entry [(:row inner) (:col inner)])))

(defn- innermost
  "The entry with the smallest range among `ranged` that contains `inner`."
  [ranged inner]
  (->> ranged
       (filter #(contains-start? % inner))
       (sort-by (juxt :row :col))
       last))

;; ---------------------------------------------------------------------------
;; Implementing type
;; ---------------------------------------------------------------------------

(defn- type-definition
  "`ns/Type` of the defrecord/deftype that contains `impl`."
  [index impl]
  (some->> (code-index/entries index :var-definitions)
           (filter (comp #{(:defined-by impl)} :defined-by))
           (remove (comp #(s/includes? % "->") str :name))
           (#(innermost % impl))
           code-index/qualified-name))

(defn- form-children
  "Zipper locations of the children of the list form starting at `row`/`col`
   in `source`."
  [source {:keys [row col]}]
  (let [form (z/find-last-by-pos (z/of-string source {:track-position? true}) [row col])
        form (if (= :list (z/tag form)) form (z/up form))]
    (take-while some? (iterate z/right (z/down form)))))

(defn- extended-type
  "The type as written in the `extend-type`/`extend-protocol` form
   `extend-usage` that contains `impl`."
  [extend-usage impl]
  (let [children (form-children (slurp (:filename impl)) extend-usage)]
    (if (= 'extend-type (:name extend-usage))
      (z/string (second children))
      (->> (drop 2 children)
           (take-while #(not= [(:row impl) (:col impl)] (z/position %)))
           (remove (comp #{:list} z/tag))
           last
           z/string))))

(defn- qualify-local-type
  "`ns/Type` when `type-str` names a record or type defined in the
   namespace of `impl`, else `type-str` as written."
  [index impl type-str]
  (or (some->> (code-index/entries index :var-definitions)
               (filter #(and (record-or-type-forms (:defined-by %))
                             (= (:impl-ns impl) (:ns %))
                             (= type-str (str (:name %)))))
               first
               code-index/qualified-name)
      type-str))

(defn- extend-type-name
  [index impl]
  (when-let [extend-usage (->> (code-index/entries index :var-usages)
                               (filter (comp extend-forms #(symbol (str (:to %)) (str (:name %)))))
                               (#(innermost % impl)))]
    (qualify-local-type index impl (extended-type extend-usage impl))))

(defn- reify-name
  [index impl]
  (str "reify"
       (some->> (innermost (code-index/entries index :var-definitions) impl)
                code-index/qualified-name
                (str " in "))))

(defn- implementing-type
  [index {:keys [defined-by] :as impl}]
  (cond (record-or-type-forms defined-by) (type-definition index impl)
        (extend-forms defined-by) (extend-type-name index impl)
        :else (reify-name index impl)))

;; ---------------------------------------------------------------------------
;; Implementations
;; ---------------------------------------------------------------------------

;; Implementation shape: {:filename "/abs/x.clj" :row 10 :label "impl.shapes/Circle" :method "area"}

(defn- protocol-implementations
  [index {protocol-ns :ns protocol-name :name}]
  (->> (code-index/entries index :protocol-impls)
       (filter #(and (= protocol-ns (:protocol-ns %)) (= protocol-name (:protocol-name %))))
       (map (fn [impl]
              {:filename (:filename impl)
               :row (:row impl)
               :label (implementing-type index impl)
               :method (str (:method-name impl))}))))

(defn- multimethod-implementations
  [index {multi-ns :ns multi-name :name}]
  (->> (code-index/entries index :var-usages)
       (filter #(and (:defmethod %) (= multi-ns (:to %)) (= multi-name (:name %))))
       (map (fn [usage]
              {:filename (:filename usage)
               :row (:row usage)
               :label (:dispatch-val-str usage)
               :method (str multi-name)}))))

(defn- implementations
  [index {:keys [kind] :as definition}]
  (->> (if (= "defprotocol" kind)
         (protocol-implementations index definition)
         (multimethod-implementations index definition))
       distinct
       (sort-by (juxt :filename :row))))

;; ---------------------------------------------------------------------------
;; Answer
;; ---------------------------------------------------------------------------

(defn- implementation->line
  [root {:keys [filename row label method]}]
  (str (output/relative-path root filename) ":" row "  " label "  " method))

(defn- not-implementable
  [name-str definitions]
  (str "not a protocol or multimethod: " name-str " ("
       (s/join ", " (map #(str (code-index/qualified-name %) " " (:kind %)) definitions))
       ")"))

(defn- no-implementations
  [index definition]
  (str "no implementations of " (code-index/qualified-name definition) " "
       (output/checked index)))

(defmethod tool/call :find_implementations
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (str (:name params))
        definitions (code-index/resolve-name code-index name-str)
        implementable (filterv (comp implementable-kinds :kind) definitions)
        root (code-index/root code-index)]
    (tool/text-result
     (cond
       (empty? definitions) (output/not-found code-index name-str)
       (empty? implementable) (not-implementable name-str definitions)
       (next implementable) (output/ambiguous config params code-index
                                              "protocols or multimethods" name-str implementable)
       :else (let [lines (mapv (partial implementation->line root)
                               (implementations code-index (first implementable)))]
               (if (seq lines)
                 (output/render config params lines)
                 (no-implementations code-index (first implementable))))))))
