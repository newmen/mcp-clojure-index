(ns mcp.code-index
  "Precise in-memory index of the project's Definitions and Usages, built
   from clj-kondo analysis (ADR-0002). Nothing here touches Qdrant or
   Ollama (ADR-0003).

   The index keeps clj-kondo's raw analysis per file, so a changed file is
   re-analysed on its own (`update-files`) and every query derives what it
   needs from `entries`. Definitions are normalised by `definitions`;
   `resolve-name` maps a name as an agent writes it (simple, alias-qualified
   or fully qualified) to the Definitions it may denote."
  (:require [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import (java.io File PushbackReader)))

(set! *warn-on-reflection* true)

;; Index shape:
;;
;; {:root       "/abs/project"            ; no trailing slash
;;  :test-paths ["/abs/project/test" ...] ; absolute Test path directories
;;  :files      {"/abs/file.clj" {:mtime    1700000000000
;;                                :analysis {:var-definitions [...]
;;                                           :var-usages [...]
;;                                           :namespace-definitions [...]
;;                                           :namespace-usages [...]
;;                                           :keywords [...]
;;                                           :protocol-impls [...]
;;                                           :symbols [...]
;;                                           :java-class-usages [...]}}}}
;;
;; Analysis entries are clj-kondo's own maps (see clj-kondo's analysis
;; docs); every entry has an absolute `:filename`.

(def ^:private analysis-config
  {:output {:canonical-paths true}
   :analysis {:arglists true
              :keywords true
              :protocol-impls true
              :symbols true
              :java-class-usages true
              :locals false
              :context false}})

(def ^:private test-file-pattern #"_test\.clj[cs]?$")

;; ---------------------------------------------------------------------------
;; Project files
;; ---------------------------------------------------------------------------

(defn- relative-to
  [root file-path]
  (subs file-path (min (count file-path) (inc (count root)))))

(defn- excluded?
  [cfg root ^File file]
  (let [relative (relative-to root (.getPath file))]
    (some #(re-find % relative) (:index/exclude cfg))))

(defn- project-file?
  [cfg root ^File file]
  (and (.isFile file)
       (some #(s/ends-with? (s/lower-case (.getPath file)) %) (:index/include-extensions cfg))
       (not (excluded? cfg root file))))

(defn- walk
  "Files under `root`, not descending into excluded directories."
  [cfg root]
  (->> (tree-seq (fn [^File file]
                   (and (.isDirectory file) (not (excluded? cfg root file))))
                 (fn [^File dir] (.listFiles dir))
                 (io/file root))
       (remove #(.isDirectory ^File %))))

(defn- source-files
  "Absolute paths of all Clojure and EDN files of the project, minus
   configured excludes."
  [cfg root]
  (->> (walk cfg root)
       (filter (partial project-file? cfg root))
       (mapv #(.getCanonicalPath ^File %))))

;; ---------------------------------------------------------------------------
;; Test paths
;; ---------------------------------------------------------------------------

(defn- read-forms
  [^File file]
  (binding [*read-eval* false]
    (with-open [reader (PushbackReader. (io/reader file))]
      (->> (repeatedly #(read {:eof ::eof :read-cond :allow} reader))
           (take-while (complement #{::eof}))
           doall))))

(defn- lein-test-paths
  [^File project-clj]
  (when (.isFile project-clj)
    (some (fn [form]
            (when (and (seq? form) (= 'defproject (first form)))
              (let [options (apply hash-map (drop 3 form))]
                (or (:test-paths options) ["test"]))))
          (read-forms project-clj))))

(defn- deps-test-paths
  [^File deps-edn]
  (when (.isFile deps-edn)
    (->> (:aliases (edn/read-string (slurp deps-edn)))
         (filter (comp #(s/includes? % "test") name key))
         (mapcat (comp :extra-paths val))
         seq)))

(defn- detect-test-paths
  "Test paths from `project.clj` `:test-paths` or the `:extra-paths` of
   `deps.edn` aliases whose name contains `test`; fallback `test`."
  [root]
  (->> (or (try (lein-test-paths (io/file root "project.clj"))
                (catch Exception _ nil))
           (try (deps-test-paths (io/file root "deps.edn"))
                (catch Exception _ nil))
           ["test"])
       (mapv #(.getCanonicalPath (io/file root (str %))))))

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(defn- kondo-config-dir
  [root]
  (let [dir (io/file root ".clj-kondo")]
    (when (.isDirectory dir)
      (.getCanonicalPath dir))))

(defn- analyze
  "Run clj-kondo on `file-paths`; returns {file-path analysis}."
  [root file-paths]
  (let [result (kondo/run! (cond-> {:lint (vec file-paths)
                                    :cache false
                                    :skip-lint true
                                    :parallel true
                                    :config analysis-config}
                             (kondo-config-dir root) (assoc :config-dir (kondo-config-dir root))))
        empty-analysis (zipmap (keys (:analysis result)) (repeat []))]
    (reduce-kv (fn [acc entry-key entries]
                 (reduce (fn [acc entry]
                           (update-in acc [(:filename entry) entry-key] (fnil conj []) entry))
                         acc
                         entries))
               (zipmap file-paths (repeat empty-analysis))
               (:analysis result))))

(defn update-files
  "Re-analyse `file-paths` (absolute) and replace their entries in `index`.
   Paths that no longer exist are dropped from the index."
  [index file-paths]
  (let [{existing true missing false} (group-by #(.isFile (io/file ^String %)) file-paths)
        analyses (when (seq existing) (analyze (:root index) existing))]
    (-> index
        (update :files #(apply dissoc % missing))
        (update :files merge
                (into {}
                      (map (fn [file-path]
                             [file-path {:mtime (.lastModified (io/file ^String file-path))
                                         :analysis (get analyses file-path)}]))
                      existing)))))

(defn build
  "Build the index of the project at `:index/root-path` from scratch."
  [cfg]
  (let [root (.getCanonicalPath (io/file ^String (:index/root-path cfg)))]
    (update-files {:root root
                   :test-paths (detect-test-paths root)
                   :files {}}
                  (source-files cfg root))))

;; ---------------------------------------------------------------------------
;; Freshness (ADR-0005)
;; ---------------------------------------------------------------------------

(defn stale-files
  "Absolute paths of the files whose index entries are out of date: files
   whose modification time differs from the indexed one, files added to the
   project and indexed files that no longer exist."
  [cfg index]
  (let [indexed (:files index)
        current (source-files cfg (:root index))
        changed-or-added (remove #(= (get-in indexed [% :mtime])
                                     (.lastModified (io/file ^String %)))
                                 current)
        deleted (remove (set current) (keys indexed))]
    (vec (concat changed-or-added deleted))))

(defn refresh
  "Bring `index` up to date with the files on disk, re-analysing only the
   stale files (see `stale-files`). Returns `index` itself when nothing
   changed. Precise tools call this before every answer."
  [cfg index]
  (let [stale (stale-files cfg index)]
    (if (seq stale)
      (update-files index stale)
      index)))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(defn files
  "Absolute paths of all indexed files."
  [index]
  (keys (:files index)))

(defn test-file?
  "True when `file-path` (absolute) lies under a Test path or is named
   like a test file."
  [index ^String file-path]
  (boolean
   (or (some #(s/starts-with? file-path (str % "/")) (:test-paths index))
       (re-find test-file-pattern file-path))))

(defn entries
  "All clj-kondo analysis entries of kind `entry-key` (e.g. `:var-usages`)
   across the indexed files."
  [index entry-key]
  (mapcat (comp entry-key :analysis) (vals (:files index))))

(defn file-entries
  "The clj-kondo analysis entries of kind `entry-key` in one indexed file
   (`file-path` absolute)."
  [index file-path entry-key]
  (get-in index [:files file-path :analysis entry-key]))

;; ---------------------------------------------------------------------------
;; Definitions
;; ---------------------------------------------------------------------------

;; Definition shape:
;;
;; {:ns       mcp.output            ; namespace of the file it is written in;
;;                                  ;  absent for a namespace Definition
;;  :name     relative-path         ; symbol; a keyword (:app.core/uuid) for a spec
;;  :dispatch ":circle"             ; only for a defmethod, as written
;;  :multimethod-ns mcp.tool        ; only for a defmethod: namespace of its
;;                                  ;  multimethod, which (with :name) names it
;;  :kind     "defn"                ; defining form ("deftest", "defmethod", "s/def",
;;                                  ;  a lint-as project macro, ...), "ns", "protocol-method"
;;  :filename "/abs/src/mcp/output.clj"
;;  :row 25 :col 1 :end-row 33 :end-col 20   ; whole form, :end-col exclusive
;;  :arglists ["[root-path file-path]"]
;;  :doc      "Return ..."
;;  :private  true}                 ; only when private
;;
;; clj-kondo reports `defmethod` and `s/def` forms only as usages, so their
;; Definitions are built from the usage of the defining macro, whose range
;; spans the whole form.

(def ^:private range-keys [:row :col :end-row :end-col])

(defn- var-definition->definition
  [{:keys [defined-by protocol-name arglist-strs] :as var-def}]
  (cond-> (select-keys var-def [:ns :name :filename :row :col :end-row :end-col :doc :private])
    true (assoc :kind (cond protocol-name "protocol-method"
                            defined-by (name defined-by)
                            :else "def"))
    (seq arglist-strs) (assoc :arglists arglist-strs)))

(defn- namespace-definition->definition
  [ns-def]
  (-> (select-keys ns-def [:name :filename :row :col :end-row :end-col :doc])
      (assoc :kind "ns")))

(defn- contains-position?
  "True when the form range of `entry` contains `position` [row col]."
  [{:keys [row col end-row end-col]} position]
  (and row end-row
       (<= (compare [row col] position) 0)
       (neg? (compare position [end-row end-col]))))

(defn- defining-form
  "The innermost usage of the macro `macro-ns`/`macro-name` in `analysis`
   whose form contains the entry `inner`."
  [analysis macro-ns macro-name inner]
  (->> (:var-usages analysis)
       (filter #(and (= macro-ns (:to %)) (= macro-name (:name %))))
       (filter #(contains-position? % [(:row inner) (:col inner)]))
       (sort-by (juxt :row :col))
       last))

(defn- defmethod-definitions
  [analysis]
  (for [method-head (:var-usages analysis)
        :when (:defmethod method-head)]
    (merge {:ns (:from method-head)
            :multimethod-ns (:to method-head)
            :name (:name method-head)
            :dispatch (:dispatch-val-str method-head)
            :kind "defmethod"
            :filename (:filename method-head)}
           (select-keys (or (defining-form analysis 'clojure.core 'defmethod method-head)
                            method-head)
                        range-keys))))

(defn- spec-definitions
  [analysis]
  (for [spec-key (:keywords analysis)
        :when (= 'clojure.spec.alpha/def (:reg spec-key))]
    (merge {:ns (:ns spec-key)
            :name (keyword (some-> (:ns spec-key) str) (:name spec-key))
            :kind "s/def"
            :filename (:filename spec-key)}
           (select-keys (or (defining-form analysis 'clojure.spec.alpha 'def spec-key)
                            spec-key)
                        range-keys))))

(defn- file-definitions
  "The Definitions of one file's clj-kondo `analysis`, one per defining form
   (a `.cljc` form analysed for several languages appears once)."
  [analysis]
  (->> (concat (map var-definition->definition (:var-definitions analysis))
               (map namespace-definition->definition (:namespace-definitions analysis))
               (defmethod-definitions analysis)
               (spec-definitions analysis))
       (map #(into {} (remove (comp nil? val)) %))
       (group-by (juxt :row :col :ns :name :dispatch))
       vals
       (map first)))

(defn definitions
  "All Definitions of the project: vars, tests, multimethods and their
   methods, protocols, records, specs and forms of project macros."
  [index]
  (mapcat (comp file-definitions :analysis) (vals (:files index))))

(defn enclosing-definition
  "The Enclosing definition of the position `row`:`col` (1-based) in
   `file-path` (absolute): the innermost Definition whose form contains it,
   or nil. Works where clj-kondo gives no `:from-var` (defmethod, s/def,
   record and type bodies)."
  [index file-path row col]
  (->> (file-definitions (get-in index [:files file-path :analysis]))
       (filter #(contains-position? % [row col]))
       ;; innermost first: latest start, then earliest end; of forms sharing
       ;; one range (`Circle`, `->Circle`) the shortest name
       (sort-by (fn [{def-row :row def-col :col :keys [end-row end-col] def-name :name}]
                  [(- def-row) (- def-col) end-row end-col (count (str def-name))]))
       first))

(defn- name-ns
  "The namespace that qualifies a Definition's name: its multimethod's for
   a defmethod, else its own."
  [{:keys [ns multimethod-ns]}]
  (or multimethod-ns ns))

(defn qualified-name
  "`ns/name` of a Definition (for a defmethod, its multimethod's `ns/name`
   followed by the dispatch value), the keyword of a spec, or the namespace
   name of a namespace Definition."
  [{:keys [dispatch] def-name :name :as definition}]
  (cond (keyword? def-name) (str def-name)
        (name-ns definition) (cond-> (str (name-ns definition) "/" def-name)
                               dispatch (str " " dispatch))
        :else (str def-name)))

(defn local-name
  "Name of a Definition as written in its own namespace: `name`, followed
   by the dispatch value for a defmethod, whose multimethod is qualified
   when it lives in another namespace."
  [{:keys [ns dispatch] def-name :name :as definition}]
  (cond-> (if (= ns (name-ns definition))
            (str def-name)
            (str (name-ns definition) "/" def-name))
    dispatch (str " " dispatch)))

;; ---------------------------------------------------------------------------
;; Name resolution
;; ---------------------------------------------------------------------------

(defn- alias-targets
  "Namespaces that `alias-str` stands for in any namespace of the project."
  [index alias-str]
  (->> (entries index :namespace-usages)
       (filter (comp #{alias-str} str :alias))
       (into #{} (map :to))))

(defn- split-name
  [name-str]
  (let [slash (s/index-of name-str "/")]
    (if (and slash (pos? slash) (< slash (dec (count name-str))))
      [(subs name-str 0 slash) (subs name-str (inc slash))]
      [nil name-str])))

(defn- normalize-dispatch
  [dispatch-str]
  (some-> dispatch-str (s/replace #"\s+" " ")))

(defn- symbol-matcher
  "Predicate on Definitions for `name-str`, optionally followed by a
   defmethod dispatch value (`area :circle`)."
  [index name-str]
  (let [[head dispatch] (s/split name-str #"\s+" 2)
        [ns-part simple] (split-name head)
        simple-sym (symbol simple)
        dispatch (normalize-dispatch dispatch)
        namespaces (when ns-part
                     (conj (alias-targets index ns-part) (symbol ns-part)))]
    (fn [definition]
      (and (= simple-sym (:name definition))
           (= dispatch (normalize-dispatch (:dispatch definition)))
           (or (nil? namespaces) (contains? namespaces (name-ns definition)))))))

(defn- keyword-matcher
  "Predicate on spec Definitions for a keyword as written: `:app.core/uuid`,
   `::uuid` (in any namespace) or `::c/uuid` (alias-qualified)."
  [index keyword-str]
  (let [auto-resolved? (s/starts-with? keyword-str "::")
        [ns-part simple] (split-name (s/replace keyword-str #"^::?" ""))
        namespaces (cond (and auto-resolved? ns-part)
                         (conj (alias-targets index ns-part) (symbol ns-part))
                         ns-part #{(symbol ns-part)})]
    (fn [{def-name :name}]
      (and (keyword? def-name)
           (= simple (name def-name))
           (cond namespaces (contains? namespaces (some-> (namespace def-name) symbol))
                 auto-resolved? (some? (namespace def-name))
                 :else (nil? (namespace def-name)))))))

(defn resolve-name
  "Definitions that `name-str` may denote: a simple name (`get-uuid`, or a
   namespace name), an alias-qualified name as written in some namespace
   (`c/get-uuid`) or a fully qualified name (`app.core/get-uuid`). A
   multimethod name followed by a dispatch value (`area :circle`) denotes
   that defmethod; a keyword (`:app.core/uuid`, `::uuid`, `::c/uuid`)
   denotes its spec. More than one result means the name is ambiguous."
  [index name-str]
  (let [name-str (s/trim (or name-str ""))]
    (when-not (or (s/blank? name-str) (= ":" name-str) (= "::" name-str))
      (->> (definitions index)
           (filter (if (s/starts-with? name-str ":")
                     (keyword-matcher index name-str)
                     (symbol-matcher index name-str)))
           (sort-by (juxt :filename :row))
           vec))))
