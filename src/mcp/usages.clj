(ns mcp.usages
  "Usages of a target, derived from the clj-kondo analysis kept in the code
   index (ADR-0002).

   Two extension points:

   - `resolve-target` turns a target as an agent writes it into a Target;
     each Target type has its own `usages` method.
   - `var-usage-kind-rules` classifies a var-usage into a Usage kind; the
     first rule whose predicate holds wins."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mcp.code-index :as code-index]
            [rewrite-clj.zip :as z]))

(set! *warn-on-reflection* true)

;; Target shape (from `resolve-target`):
;;
;; {:target/type :var                ; or :namespace, :spec, :defmethod
;;  :target/name "app.core/get-uuid" ; how the answer names it
;;  :definition  {...}}              ; a Definition, see mcp.code-index
;;
;; Usage shape (from `usages`):
;;
;; {:filename  "/abs/src/app/other.clj"
;;  :row 6 :col 5                    ; start of the referring symbol
;;  :kind      "call"                ; Usage kind, see GLOSSARY.md
;;  :enclosing "app.other/caller"}   ; Enclosing definition, or the namespace

;; ---------------------------------------------------------------------------
;; Positions
;; ---------------------------------------------------------------------------

(defn- position
  "[row col] of the symbol of a clj-kondo entry."
  [entry]
  [(or (:name-row entry) (:row entry)) (or (:name-col entry) (:col entry))])

(defn- encloses?
  "True when the form range of `entry` contains `pos` (`:end-col` exclusive)."
  [entry pos]
  (and (<= (compare [(:row entry) (:col entry)] pos) 0)
       (neg? (compare pos [(:end-row entry) (:end-col entry)]))))

(defn- core-macro-usages
  "Usages of `clojure.core/<macro-name>` in the file of `ctx`: the ranges of
   those forms."
  [{:keys [file-usages]} macro-name]
  (filter #(and (= 'clojure.core (:to %)) (= macro-name (:name %))) file-usages))

;; ---------------------------------------------------------------------------
;; File context
;; ---------------------------------------------------------------------------

;; Per-file context shared by the kind rules and the Enclosing definition:
;;
;; {:index       code index
;;  :filename    "/abs/file.clj"
;;  :file-usages var-usages of the file
;;  :lines       delay of the file's source lines}

(defn- file-context
  [index filename]
  {:index index
   :filename filename
   :file-usages (code-index/file-entries index filename :var-usages)
   :lines (delay (with-open [reader (io/reader (io/file ^String filename))]
                   (vec (line-seq reader))))})

;; ---------------------------------------------------------------------------
;; Usage kinds
;; ---------------------------------------------------------------------------

(defn- preceded-by-var-quote?
  [{:keys [lines]} var-usage]
  (let [[row col] (position var-usage)
        line (get @lines (dec row) "")]
    (and (>= col 3)
         (= "#'" (subs line (- col 3) (dec col))))))

(defn- var-ref?
  "`#'name` or `(var name)`."
  [ctx var-usage]
  (or (preceded-by-var-quote? ctx var-usage)
      (some #(encloses? % (position var-usage)) (core-macro-usages ctx 'var))))

(defn- in-comment?
  "Inside a `(comment ...)` form."
  [ctx var-usage]
  (some #(encloses? % (position var-usage)) (core-macro-usages ctx 'comment)))

(defn- redef-key-positions
  "[row col] of each binding key of the `with-redefs` form starting at
   `row`/`col` in `source`; `#'name` keys give the position of `name`."
  [source {:keys [row col]}]
  (let [loc (z/find-last-by-pos (z/of-string source {:track-position? true}) [row col])
        form (if (= :list (z/tag loc)) loc (z/up loc))
        bindings (-> form z/down z/right)]
    (->> (iterate z/right (z/down bindings))
         (take-while some?)
         (take-nth 2)
         (map #(z/position (if (= :var (z/tag %)) (z/down %) %))))))

(defn- redef?
  "A binding key of an enclosing `with-redefs`. clj-kondo gives the form's
   range but not its bindings, so those are read from the source; only
   files with an enclosing `with-redefs` (usually tests) are parsed."
  [{:keys [lines] :as ctx} var-usage]
  (let [pos (position var-usage)]
    (when-let [forms (seq (filter #(encloses? % pos) (core-macro-usages ctx 'with-redefs)))]
      (let [source (str/join "\n" @lines)]
        (some #(some #{pos} (redef-key-positions source %)) forms)))))

(def ^:private var-usage-kind-rules
  "[kind (fn [ctx var-usage] truthy)], tried in order; the last always holds."
  [["require" (fn [_ctx var-usage] (:refer var-usage))]
   ["in-comment" in-comment?]
   ["redef" redef?]
   ["var-ref" var-ref?]
   ["call" (fn [_ctx var-usage] (:arity var-usage))]
   ["value" (constantly true)]])

(defn- var-usage-kind
  [ctx var-usage]
  (some (fn [[kind holds?]] (when (holds? ctx var-usage) kind))
        var-usage-kind-rules))

;; ---------------------------------------------------------------------------
;; Enclosing definition
;; ---------------------------------------------------------------------------

(defn- enclosing-definition
  "Qualified name of the Definition whose body contains `entry`; the
   namespace when the entry lies outside every Definition."
  [{:keys [index filename]} entry]
  (let [[row col] (position entry)]
    (or (when-let [var-name (:from-var entry)]
          (str (:from entry) "/" var-name))
        (some-> (code-index/enclosing-definition index filename row col)
                code-index/qualified-name)
        (str (:from entry)))))

;; ---------------------------------------------------------------------------
;; Targets
;; ---------------------------------------------------------------------------

(defn- definition-target-type
  [{:keys [ns dispatch] def-name :name}]
  (cond (keyword? def-name) :spec
        dispatch :defmethod
        ns :var
        :else :namespace))

(defn resolve-target
  "Resolve a target as written by an agent. Returns {:target t} for one
   Target, {:candidates [Definition ...]} for an ambiguous name, or {} when
   nothing matches."
  [index target-str]
  (let [definitions (code-index/resolve-name index target-str)]
    (case (count definitions)
      0 {}
      1 (let [definition (first definitions)]
          {:target {:target/type (definition-target-type definition)
                    :target/name (code-index/qualified-name definition)
                    :definition definition}})
      {:candidates definitions})))

(defn- usage
  [ctx kind entry]
  {:filename (:filename entry)
   :row (first (position entry))
   :col (second (position entry))
   :kind kind
   :enclosing (enclosing-definition ctx entry)})

(defn- file-usages
  "Usages built from `entries` (all of one target), file by file, with
   `kind-fn` of (ctx entry)."
  [index kind-fn entries]
  (->> (group-by :filename entries)
       (mapcat (fn [[filename file-entries]]
                 (let [ctx (file-context index filename)]
                   (map #(usage ctx (kind-fn ctx %) %) file-entries))))))

(defmulti usages
  "All Usages of a Target, sorted by file and position, without duplicates
   (a `.cljc` form analysed for several languages appears once)."
  (fn [_index target] (:target/type target)))

(defn- sorted-distinct
  [found]
  (->> found
       (group-by (juxt :filename :row :col :kind))
       vals
       (map first)
       (sort-by (juxt :filename :row :col))))

(defmethod usages :default
  [_index _target]
  ;; TODO: spec and defmethod Targets have no Usages until keyword targets
  ;; (ticket 05) are supported
  [])

(defmethod usages :var
  [index {{def-ns :ns def-name :name} :definition}]
  (->> (code-index/entries index :var-usages)
       (filter #(and (= def-ns (:to %)) (= def-name (:name %))))
       (filter :name-row)               ; synthetic arity-check duplicates have none
       (remove :defmethod)              ; a defmethod head is an Implementation
       (file-usages index var-usage-kind)
       sorted-distinct))

(defmethod usages :namespace
  [index {{ns-name :name} :definition}]
  (->> (code-index/entries index :namespace-usages)
       (filter (comp #{ns-name} :to))
       (file-usages index (constantly "require"))
       sorted-distinct))

(defn kind-names
  "The Usage kinds `usages` can report."
  []
  (mapv first var-usage-kind-rules))
