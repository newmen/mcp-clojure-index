(ns mcp.usages
  "Usages of a target, derived from the clj-kondo analysis kept in the code
   index (ADR-0002).

   Two extension points:

   - `resolve-target` turns a target as an agent writes it into a Target;
     each Target type has its own `usages` method.
   - `var-usage-kind-rules` classifies a var-usage into a Usage kind; the
     first rule whose predicate holds wins."
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [mcp.code-index :as code-index]
            [rewrite-clj.zip :as z]))

(set! *warn-on-reflection* true)

;; Target shape (from `resolve-target`):
;;
;; {:target/type :var                ; or :namespace, :spec, :defmethod, :keyword
;;  :target/name "app.core/get-uuid" ; how the answer names it
;;  :definition  {...}               ; a Definition, see mcp.code-index
;;  :keyword     {:ns app.core       ; only for :keyword (no Definition);
;;                :name "uuid"}}     ; :ns a symbol or nil
;;
;; A Usage in an EDN file has an empty :enclosing.
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
      (let [source (s/join "\n" @lines)]
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

(defn- keyword-target
  [keyword-ns keyword-name]
  {:target/type :keyword
   :target/name (str (keyword (some-> keyword-ns str) keyword-name))
   :keyword {:ns keyword-ns :name keyword-name}})

(defn- auto-resolved-namespaces
  "Namespaces that `::alias/name` (or `::name` when `alias-str` is nil)
   resolves to somewhere in the project."
  [index alias-str keyword-name]
  (->> (code-index/entries index :keywords)
       (filter #(and (:auto-resolved %) (:ns %) (= keyword-name (:name %))))
       (filter #(if alias-str
                  (contains? #{(str (:alias %)) (str (:ns %))} alias-str)
                  (nil? (:alias %))))
       (into (sorted-set) (map :ns))))

(defn- resolve-keyword
  "A keyword without a spec: `:name` and `:ns/name` always denote a
   Target; `::name` and `::alias/name` denote the namespaces they resolve
   to in the project."
  [index keyword-str]
  (let [auto-resolved? (s/starts-with? keyword-str "::")
        [ns-part keyword-name] (let [bare (s/replace keyword-str #"^::?" "")
                                     slash (s/index-of bare "/")]
                                 (if (and slash (pos? slash))
                                   [(subs bare 0 slash) (subs bare (inc slash))]
                                   [nil bare]))]
    (cond
      (s/blank? keyword-name) {}
      (not auto-resolved?) {:target (keyword-target (some-> ns-part symbol) keyword-name)}
      :else (let [namespaces (auto-resolved-namespaces index ns-part keyword-name)]
              (case (count namespaces)
                0 {}
                1 {:target (keyword-target (first namespaces) keyword-name)}
                {:candidates (map #(hash-map :name (keyword (str %) keyword-name)) namespaces)})))))

(defn resolve-target
  "Resolve a target as written by an agent. Returns {:target t} for one
   Target, {:candidates [Definition ...]} for an ambiguous name, or {} when
   nothing matches. A keyword without a spec is a Target of its own."
  [index target-str]
  (let [definitions (code-index/resolve-name index target-str)]
    (case (count definitions)
      0 (if (s/starts-with? (s/trim (or target-str "")) ":")
          (resolve-keyword index (s/trim target-str))
          {})
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
   (a `.cljc` form analysed for several languages appears once). Only for
   a Target that `has-usages?`."
  (fn [_index target] (:target/type target)))

(defn has-usages?
  "False for a defmethod Target, which is never referred to by itself:
   calls go through its multimethod."
  [target]
  (contains? (methods usages) (:target/type target)))

(defn- sorted-distinct
  [found]
  (->> found
       (group-by (juxt :filename :row :col :kind))
       vals
       (map first)
       (sort-by (juxt :filename :row :col))))

(defn- in-ns-form?
  "True when a keyword entry lies inside the `ns` form of its file
   (`:require`, `:as`, ...)."
  [index keyword-entry]
  (some #(encloses? % (position keyword-entry))
        (code-index/file-entries index (:filename keyword-entry) :namespace-definitions)))

(defn- keyword-kind
  [_ctx keyword-entry]
  (if (s/ends-with? (:filename keyword-entry) ".edn") "edn" "keyword"))

(defn- keyword-usages
  "Usages of the keyword `:keyword-ns/keyword-name` (`keyword-ns` a symbol
   or nil) in code and in EDN files; the `s/def` registering it is its
   Definition, not a Usage."
  [index keyword-ns keyword-name]
  (->> (code-index/entries index :keywords)
       (filter #(and (= keyword-name (:name %)) (= keyword-ns (:ns %))))
       (remove #(and (:auto-resolved %) (nil? (:ns %)))) ; `::k` in EDN resolves nowhere
       (remove (some-fn :reg :keys-destructuring-ns-modifier))
       (remove (partial in-ns-form? index))
       (file-usages index keyword-kind)
       sorted-distinct))

(defmethod usages :keyword
  [index {{keyword-ns :ns keyword-name :name} :keyword}]
  (keyword-usages index keyword-ns keyword-name))

(defmethod usages :spec
  [index {{spec-key :name} :definition}]
  (keyword-usages index (some-> (namespace spec-key) symbol) (name spec-key)))

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
  (conj (mapv first var-usage-kind-rules) "keyword" "edn"))
