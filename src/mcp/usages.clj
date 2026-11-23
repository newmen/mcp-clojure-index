(ns mcp.usages
  "Usages of a target, derived from the clj-kondo analysis kept in the code
   index (ADR-0002).

   Two extension points:

   - `resolve-target` turns a target as an agent writes it into a Target;
     each Target type has its own `usages` method.
   - `var-usage-kind-rules` classifies a var-usage into a Usage kind; the
     first rule whose predicate holds wins."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
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
;;  :enclosing "app.other/caller"    ; Enclosing definition, or the namespace
;;  :warning   "wrong arity: 3 args, takes 1|2"}  ; Usage warning, only when one holds

(s/def ::target
  (s/keys :req [:target/type :target/name]
          :opt-un [::code-index/definition ::keyword]))

(s/def ::usage-kind
  #{"require" "in-comment" "redef" "var-ref" "call" "value" "keyword" "edn"})

(s/def ::usage (s/keys :req-un [::filename ::row ::col ::kind ::enclosing] :opt-un [::warning]))

;; What `resolve-target` returns: {:target Target} for one Target,
;; {:candidates [Definition]} for an ambiguous name, {} for no match.

(s/def ::target-resolution (s/keys :opt-un [::target ::candidates]))

;; ---------------------------------------------------------------------------
;; Positions
;; ---------------------------------------------------------------------------

(defn- position
  "The [row col] of the symbol of a clj-kondo entry.

  AnalysisEntry -> [Long Long]"
  [entry]
  [(or (:name-row entry) (:row entry)) (or (:name-col entry) (:col entry))])

(defn- core-macro-usages
  "The usages of `clojure.core/<macro-name>` in the file of `ctx`, whose
   ranges are those of the macro's forms.

  FileContext -> Symbol -> [AnalysisEntry]"
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

(s/def ::file-context (s/keys :req-un [::index ::filename ::file-usages ::lines]))

(defn- file-context
  "The FileContext of `filename`; its source lines are read on first use.

  CodeIndex -> String -> FileContext"
  [index filename]
  {:index index
   :filename filename
   :file-usages (code-index/file-entries index filename :var-usages)
   :lines (delay (code-index/file-lines filename))})

;; ---------------------------------------------------------------------------
;; Usage kinds
;; ---------------------------------------------------------------------------

(defn- preceded-by-var-quote?
  "True when `#'` stands right before the symbol of `var-usage`.

  FileContext -> AnalysisEntry -> IO Boolean"
  [{:keys [lines]} var-usage]
  (let [[row col] (position var-usage)
        line (get @lines (dec row) "")]
    (and (>= col 3)
         (= "#'" (subs line (- col 3) (dec col))))))

(defn- var-ref?
  "True when `var-usage` is written `#'name` or `(var name)`.

  FileContext -> AnalysisEntry -> IO Boolean"
  [ctx var-usage]
  (or (preceded-by-var-quote? ctx var-usage)
      (some #(code-index/contains-position? % (position var-usage)) (core-macro-usages ctx 'var))))

(defn- in-comment?
  "True when `var-usage` lies inside a `(comment ...)` form.

  FileContext -> AnalysisEntry -> Boolean"
  [ctx var-usage]
  (some #(code-index/contains-position? % (position var-usage)) (core-macro-usages ctx 'comment)))

(defn- redef-key-positions
  "The [row col] of each binding key of the `with-redefs` form starting at
   `row`/`col` in `source`; `#'name` keys give the position of `name`.

  String -> FormRange -> [[Long Long]]"
  [source {:keys [row col]}]
  (let [loc (z/find-last-by-pos (z/of-string source {:track-position? true}) [row col])
        form (if (= :list (z/tag loc)) loc (z/up loc))
        bindings (-> form z/down z/right)]
    (->> (iterate z/right (z/down bindings))
         (take-while some?)
         (take-nth 2)
         (map #(z/position (if (= :var (z/tag %)) (z/down %) %))))))

(defn- redef?
  "True when `var-usage` is a binding key of an enclosing `with-redefs`.

  clj-kondo gives the form's range but not its bindings, so those are read
  from the source; only files with an enclosing `with-redefs` (usually
  tests) are parsed.

  FileContext -> AnalysisEntry -> IO Boolean"
  [{:keys [lines] :as ctx} var-usage]
  (let [pos (position var-usage)]
    (when-let [forms (seq (filter #(code-index/contains-position? % pos) (core-macro-usages ctx 'with-redefs)))]
      (let [source (st/join "\n" @lines)]
        (some #(some #{pos} (redef-key-positions source %)) forms)))))

(def ^:private var-usage-kind-rules
  "The rules that classify a var-usage into a Usage kind, tried in order;
   the last always holds.

  [[UsageKind, (FileContext -> AnalysisEntry -> IO Boolean)]]"
  [["require" (fn [_ctx var-usage] (:refer var-usage))]
   ["in-comment" in-comment?]
   ["redef" redef?]
   ["var-ref" var-ref?]
   ["call" (fn [_ctx var-usage] (:arity var-usage))]
   ["value" (constantly true)]])

(defn- var-usage-kind
  "The Usage kind of `var-usage`: that of the first rule that holds.

  FileContext -> AnalysisEntry -> IO UsageKind"
  [ctx var-usage]
  (some (fn [[kind holds?]] (when (holds? ctx var-usage) kind))
        var-usage-kind-rules))

;; ---------------------------------------------------------------------------
;; Enclosing definition
;; ---------------------------------------------------------------------------

(defn- enclosing-definition
  "The qualified name of the Definition whose body contains `entry`; the
   namespace when the entry lies outside every Definition.

  FileContext -> AnalysisEntry -> String"
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
  "The `:target/type` of a Target that is `definition`.

  Definition -> Keyword"
  [{:keys [ns dispatch] def-name :name}]
  (cond (keyword? def-name) :spec
        dispatch :defmethod
        ns :var
        :else :namespace))

(defn- keyword-target
  "The Target of a keyword without a spec.

  Maybe Symbol -> String -> Target"
  [keyword-ns keyword-name]
  {:target/type :keyword
   :target/name (str (keyword (some-> keyword-ns str) keyword-name))
   :keyword {:ns keyword-ns :name keyword-name}})

(defn- auto-resolved-namespaces
  "Namespaces that `::alias/name` (or `::name` when `alias-str` is nil)
   resolves to somewhere in the project.

  CodeIndex -> Maybe String -> String -> #{Symbol}"
  [index alias-str keyword-name]
  (->> (code-index/entries index :keywords)
       (filter #(and (:auto-resolved %) (:ns %) (= keyword-name (:name %))))
       (filter #(if alias-str
                  (contains? #{(str (:alias %)) (str (:ns %))} alias-str)
                  (nil? (:alias %))))
       (into (sorted-set) (map :ns))))

(defn- resolve-keyword
  "The TargetResolution of a keyword that no spec defines.

  `:name` and `:ns/name` always denote a Target; `::name` and
  `::alias/name` denote the namespaces they resolve to in the project.

  CodeIndex -> String -> TargetResolution"
  [index keyword-str]
  (let [auto-resolved? (st/starts-with? keyword-str "::")
        [ns-part keyword-name] (code-index/split-name (st/replace keyword-str #"^::?" ""))]
    (cond
      (st/blank? keyword-name) {}
      (not auto-resolved?) {:target (keyword-target (some-> ns-part symbol) keyword-name)}
      :else (let [namespaces (auto-resolved-namespaces index ns-part keyword-name)]
              (case (count namespaces)
                0 {}
                1 {:target (keyword-target (first namespaces) keyword-name)}
                {:candidates (map #(hash-map :name (keyword (str %) keyword-name)) namespaces)})))))

(defn resolve-target
  "Resolves a target as written by an agent. Returns {:target t} for one
   Target, {:candidates [Definition ...]} for an ambiguous name, or {} when
   nothing matches. A keyword without a spec is a Target of its own.

  CodeIndex -> Maybe String -> TargetResolution"
  [index target-str]
  (let [definitions (code-index/resolve-name index target-str)]
    (case (count definitions)
      0 (if (st/starts-with? (st/trim (or target-str "")) ":")
          (resolve-keyword index (st/trim target-str))
          {})
      1 (let [definition (first definitions)]
          {:target {:target/type (definition-target-type definition)
                    :target/name (code-index/qualified-name definition)
                    :definition definition}})
      {:candidates definitions})))

(defn- usage
  "The Usage of the clj-kondo `entry`, of Usage kind `kind`, with the Usage
   warning that `warning-fn` gives, if any.

  FileContext -> (UsageKind -> AnalysisEntry -> Maybe String) -> UsageKind -> AnalysisEntry -> Usage"
  [ctx warning-fn kind entry]
  (let [warning (warning-fn kind entry)]
    (cond-> {:filename (:filename entry)
             :row (first (position entry))
             :col (second (position entry))
             :kind kind
             :enclosing (enclosing-definition ctx entry)}
      warning (assoc :warning warning))))

(defn- file-usages
  "Builds the Usages of `entries` (all of one target), file by file, with
   the Usage kind that `kind-fn` gives and the Usage warning that
   `warning-fn` gives.

  CodeIndex -> (FileContext -> AnalysisEntry -> IO UsageKind) -> [AnalysisEntry] -> IO [Usage]
  CodeIndex -> (FileContext -> AnalysisEntry -> IO UsageKind) -> (UsageKind -> AnalysisEntry -> Maybe String) -> [AnalysisEntry] -> IO [Usage]"
  ([index kind-fn entries]
   (file-usages index kind-fn (constantly nil) entries))
  ([index kind-fn warning-fn entries]
   (->> (group-by :filename entries)
        (mapcat (fn [[filename file-entries]]
                  (let [ctx (file-context index filename)]
                    (map #(usage ctx warning-fn (kind-fn ctx %) %) file-entries)))))))

;; ---------------------------------------------------------------------------
;; Usage warnings
;; ---------------------------------------------------------------------------

(defn- plural
  "`n` followed by `word`, with an `s` unless `n` is 1.

  Long -> String -> String"
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- arity-warning
  "The `wrong arity` Usage warning of a `call` whose argument count matches
   no arity of `definition`; nil for a matching call, another Usage kind or
   a Definition without known arities.

  Definition -> UsageKind -> AnalysisEntry -> Maybe String"
  [{:keys [fixed-arities varargs-min-arity]} kind {:keys [arity]}]
  (when (and (= "call" kind)
             arity
             (or (seq fixed-arities) varargs-min-arity)
             (not (contains? (set fixed-arities) arity))
             (not (and varargs-min-arity (<= varargs-min-arity arity))))
    (str "wrong arity: " (plural arity "arg") ", takes "
         (st/join "|" (cond-> (mapv str (sort fixed-arities))
                        varargs-min-arity (conj (str varargs-min-arity "+")))))))

(defmulti usages
  "All Usages of a Target, sorted by file and position, without duplicates
   (a `.cljc` form analysed for several languages appears once). Only for
   a Target that `has-usages?`.

  CodeIndex -> Target -> IO [Usage]"
  (fn [_index target] (:target/type target)))

(defn has-usages?
  "True when `usages` can find the Usages of `target`. False for a
   defmethod Target, which is never referred to by itself: calls go through
   its multimethod.

  Maybe Target -> Boolean"
  [target]
  (contains? (methods usages) (:target/type target)))

(defn- sorted-distinct
  "The Usages in `found` without duplicates, sorted by file and position.

  [Usage] -> [Usage]"
  [found]
  (->> found
       (group-by (juxt :filename :row :col :kind))
       vals
       (map first)
       (sort-by (juxt :filename :row :col))))

(defn- in-ns-form?
  "True when a keyword entry lies inside the `ns` form of its file
   (`:require`, `:as`, ...).

  CodeIndex -> AnalysisEntry -> Boolean"
  [index keyword-entry]
  (some #(code-index/contains-position? % (position keyword-entry))
        (code-index/file-entries index (:filename keyword-entry) :namespace-definitions)))

(defn- keyword-kind
  "The Usage kind of a keyword entry: `edn` in an EDN file, else `keyword`.

  FileContext -> AnalysisEntry -> UsageKind"
  [_ctx keyword-entry]
  (if (st/ends-with? (:filename keyword-entry) ".edn") "edn" "keyword"))

(defn- keyword-usages
  "Usages of the keyword `:keyword-ns/keyword-name` (`keyword-ns` a symbol
   or nil) in code and in EDN files; the `s/def` registering it is its
   Definition, not a Usage.

  CodeIndex -> Maybe Symbol -> String -> IO [Usage]"
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
  [index {{def-ns :ns def-name :name :as definition} :definition}]
  (->> (code-index/entries index :var-usages)
       (filter #(and (= def-ns (:to %)) (= def-name (:name %))))
       (filter :name-row)               ; synthetic arity-check duplicates have none
       (remove :defmethod)              ; a defmethod head is an Implementation
       (file-usages index var-usage-kind (partial arity-warning definition))
       sorted-distinct))

(defmethod usages :namespace
  [index {{ns-name :name} :definition}]
  (->> (code-index/entries index :namespace-usages)
       (filter (comp #{ns-name} :to))
       (file-usages index (constantly "require"))
       sorted-distinct))

(defn kind-names
  "The Usage kinds `usages` can report.

  () -> [UsageKind]"
  []
  (conj (mapv first var-usage-kind-rules) "keyword" "edn"))
