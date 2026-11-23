(ns mcp.code-index
  "Precise in-memory index of the project's Definitions and Usages, built
   from clj-kondo analysis (ADR-0002). Nothing here touches Qdrant or
   Ollama (ADR-0003).

   The index keeps clj-kondo's raw analysis per file, so a changed file is
   re-analysed on its own (`update-files`). Each file also keeps its
   Definitions, built once when the file is analysed and listed by
   `definitions`; every other query derives what it needs from `entries`.
   `resolve-name` maps a name as an agent writes it (simple, alias-qualified
   or fully qualified) to the Definitions it may denote."
  (:require [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as st])
  (:import (java.io File PushbackReader)))

(set! *warn-on-reflection* true)

;; CodeIndex shape:
;;
;; {:root       "/abs/project"            ; no trailing slash
;;  :test-paths ["/abs/project/test" ...] ; absolute Test path directories
;;  :test-names? true                     ; no Test path declared: `*_test` files
;;                                        ;  are tests too (fallback)
;;  :files      {"/abs/file.clj" {:mtime    1700000000000
;;                                :analysis {:var-definitions [...]
;;                                           :var-usages [...]
;;                                           :namespace-definitions [...]
;;                                           :namespace-usages [...]
;;                                           :keywords [...]
;;                                           :protocol-impls [...]
;;                                           :symbols [...]
;;                                           :java-class-usages [...]}
;;                                :definitions [...]}}}  ; Definitions of the
;;                                                       ;  analysis (Definition
;;                                                       ;  shape below)
;;
;; Analysis entries are clj-kondo's own maps (see clj-kondo's analysis
;; docs); every entry has an absolute `:filename`.

(s/def ::analysis-entry (s/keys :req-un [::filename]))

(s/def ::analysis (s/map-of keyword? (s/coll-of ::analysis-entry)))

(s/def ::definitions (s/coll-of ::definition :kind vector?))

;; FileEntry: one value of `:files` in the shape above.

(s/def ::file (s/keys :req-un [::mtime ::analysis ::definitions]))

(s/def ::files (s/map-of string? ::file))

(s/def ::code-index (s/keys :req-un [::root ::test-paths ::test-names? ::files]))

;; A form range: where a form starts and ends, `:end-col` exclusive.

(s/def ::form-range (s/keys :req-un [::row ::col ::end-row ::end-col]))

;; TestLayout: where the tests of a project are, as `detect-test-paths`
;; finds them, {:test-paths [...] :test-names? false}.

(s/def ::test-layout (s/keys :req-un [::test-paths ::test-names?]))

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
  "The path of `file-path` relative to the directory `root`.

  String -> String -> String"
  [root file-path]
  (subs file-path (min (count file-path) (inc (count root)))))

(defn- excluded?
  "True when the path of `file` relative to `root` matches a configured
   exclude pattern.

  Config -> String -> File -> Boolean"
  [cfg root ^File file]
  (let [relative (relative-to root (.getPath file))]
    (some #(re-find % relative) (:index/exclude cfg))))

(defn- project-file?
  "True when `file` is a regular file with an indexed extension that is not
   excluded.

  Config -> String -> File -> IO Boolean"
  [cfg root ^File file]
  (and (.isFile file)
       (some #(st/ends-with? (st/lower-case (.getPath file)) %) (:index/include-extensions cfg))
       (not (excluded? cfg root file))))

(defn- walk
  "Lists the files under `root`, not descending into excluded directories.

  Config -> String -> IO [File]"
  [cfg root]
  (->> (tree-seq (fn [^File file]
                   (and (.isDirectory file) (not (excluded? cfg root file))))
                 (fn [^File dir] (.listFiles dir))
                 (io/file root))
       (remove #(.isDirectory ^File %))))

(defn- source-files
  "Lists the absolute paths of all Clojure and EDN files of the project,
   minus configured excludes.

  Config -> String -> IO [String]"
  [cfg root]
  (->> (walk cfg root)
       (filter (partial project-file? cfg root))
       (mapv #(.getCanonicalPath ^File %))))

;; ---------------------------------------------------------------------------
;; Test paths
;; ---------------------------------------------------------------------------

(defn- read-forms
  "Reads every top-level form of `file`, without evaluation.

  File -> IO [Any]"
  [^File file]
  (binding [*read-eval* false]
    (with-open [reader (PushbackReader. (io/reader file))]
      (->> (repeatedly #(read {:eof ::eof :read-cond :allow} reader))
           (take-while (complement #{::eof}))
           doall))))

(defn- lein-test-paths
  "Reads the `:test-paths` of the `defproject` form in `project-clj`; nil
   when the file or the key is missing.

  File -> IO (Maybe [String])"
  [^File project-clj]
  (when (.isFile project-clj)
    (some (fn [form]
            (when (and (seq? form) (= 'defproject (first form)))
              (seq (:test-paths (apply hash-map (drop 3 form))))))
          (read-forms project-clj))))

(defn- deps-test-paths
  "Reads the `:extra-paths` of the `deps.edn` aliases whose name contains
   `test`; nil when there are none.

  File -> IO (Maybe [String])"
  [^File deps-edn]
  (when (.isFile deps-edn)
    (->> (:aliases (edn/read-string (slurp deps-edn)))
         (filter (comp #(st/includes? % "test") name key))
         (mapcat (comp :extra-paths val))
         seq)))

(defn- canonical-paths
  "Canonical absolute paths of `paths`, which are relative to `root`.

  String -> [String] -> IO [String]"
  [root paths]
  (mapv #(.getCanonicalPath (io/file ^String root (str %))) paths))

(defn- detect-test-paths
  "Finds the Test paths of the project at `root`.

  They come from `project.clj` `:test-paths` or the `:extra-paths` of
  `deps.edn` aliases whose name contains `test`; the fallback is `test` and
  `*_test` files (`:test-names?`).

  String -> IO TestLayout"
  [root]
  (if-let [declared (or (try (lein-test-paths (io/file root "project.clj"))
                             (catch Exception _ nil))
                        (try (deps-test-paths (io/file root "deps.edn"))
                             (catch Exception _ nil)))]
    {:test-paths (canonical-paths root declared) :test-names? false}
    {:test-paths (canonical-paths root ["test"]) :test-names? true}))

;; ---------------------------------------------------------------------------
;; Definitions of a file
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
;;  :fixed-arities #{2}             ; only for a function or macro: its fixed
;;  :varargs-min-arity 1            ;  arities and the least argument count of
;;                                  ;  its varargs arity (clj-kondo)
;;  :doc      "Return ..."
;;  :private  true}                 ; only when private
;;
;; clj-kondo reports `defmethod` and `s/def` forms only as usages, so their
;; Definitions are built from the usage of the defining macro, whose range
;; spans the whole form.

(s/def ::definition
  (s/keys :req-un [::name ::kind ::filename ::row ::col ::end-row ::end-col]
          :opt-un [::ns ::dispatch ::multimethod-ns ::arglists ::doc ::private
                   ::fixed-arities ::varargs-min-arity]))

(def ^:private range-keys [:row :col :end-row :end-col])

(defn- var-definition->definition
  "The Definition of a clj-kondo var definition entry.

  AnalysisEntry -> Definition"
  [{:keys [defined-by protocol-name arglist-strs] :as var-def}]
  (cond-> (select-keys var-def [:ns :name :filename :row :col :end-row :end-col :doc :private
                                :fixed-arities :varargs-min-arity])
    true (assoc :kind (cond protocol-name "protocol-method"
                            defined-by (name defined-by)
                            :else "def"))
    (seq arglist-strs) (assoc :arglists arglist-strs)))

(defn- namespace-definition->definition
  "The Definition of a clj-kondo namespace definition entry.

  AnalysisEntry -> Definition"
  [ns-def]
  (-> (select-keys ns-def [:name :filename :row :col :end-row :end-col :doc])
      (assoc :kind "ns")))

(defn contains-position?
  "True when the form range of `entry` contains `position` [row col].

  FormRange -> [Long Long] -> Boolean"
  [{:keys [row col end-row end-col]} position]
  (and row end-row
       (<= (compare [row col] position) 0)
       (neg? (compare position [end-row end-col]))))

(defn- defining-form
  "The innermost usage of the macro `macro-ns`/`macro-name` in `analysis`
   whose form contains the entry `inner`, or nil.

  Analysis -> Symbol -> Symbol -> AnalysisEntry -> Maybe AnalysisEntry"
  [analysis macro-ns macro-name inner]
  (->> (:var-usages analysis)
       (filter #(and (= macro-ns (:to %)) (= macro-name (:name %))))
       (filter #(contains-position? % [(:row inner) (:col inner)]))
       (sort-by (juxt :row :col))
       last))

(defn- defmethod-definitions
  "The Definitions of the `defmethod` forms of one file's `analysis`.

  Analysis -> [Definition]"
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
  "The Definitions of the `s/def` forms of one file's `analysis`.

  Analysis -> [Definition]"
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
  "The Definitions of one file's clj-kondo `analysis`, one per defining form.

  A `.cljc` form analysed for several languages appears once.

  Analysis -> [Definition]"
  [analysis]
  (->> (concat (map var-definition->definition (:var-definitions analysis))
               (map namespace-definition->definition (:namespace-definitions analysis))
               (defmethod-definitions analysis)
               (spec-definitions analysis))
       (map #(into {} (remove (comp nil? val)) %))
       (group-by (juxt :row :col :ns :name :dispatch))
       vals
       (map first)))

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(defn- kondo-config-dir
  "The project's `.clj-kondo` directory, or nil when it has none.

  String -> IO (Maybe String)"
  [root]
  (let [dir (io/file root ".clj-kondo")]
    (when (.isDirectory dir)
      (.getCanonicalPath dir))))

(defn- analyze
  "Runs clj-kondo on `file-paths` and groups its analysis by file.

  String -> [String] -> IO {String Analysis}"
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

(defn- file-entry
  "The index entry of the file `file-path` (absolute) analysed as `analysis`:
   its mtime, the analysis and the Definitions built from it.

  String -> Analysis -> IO FileEntry"
  [^String file-path analysis]
  {:mtime (.lastModified (io/file file-path))
   :analysis analysis
   :definitions (vec (file-definitions analysis))})

(defn update-files
  "Re-analyses `file-paths` (absolute) and replaces their entries and
   Definitions in `index`. Paths that no longer exist are dropped from the
   index.

  CodeIndex -> [String] -> IO CodeIndex"
  [index file-paths]
  (let [{existing true missing false} (group-by #(.isFile (io/file ^String %)) file-paths)
        analyses (when (seq existing) (analyze (:root index) existing))]
    (-> index
        (update :files #(apply dissoc % missing))
        (update :files merge
                (into {}
                      (map (fn [file-path]
                             [file-path (file-entry file-path (get analyses file-path))]))
                      existing)))))

(defn build
  "Builds the index of the project at `:index/root-path` from scratch.

  Config -> IO CodeIndex"
  [cfg]
  (let [root (.getCanonicalPath (io/file ^String (:index/root-path cfg)))]
    (update-files (assoc (detect-test-paths root)
                         :root root
                         :files {})
                  (source-files cfg root))))

;; ---------------------------------------------------------------------------
;; Freshness (ADR-0005)
;; ---------------------------------------------------------------------------

(defn stale-files
  "Lists the absolute paths of the files whose index entries are out of
   date.

  These are files whose modification time differs from the indexed one,
  files added to the project and indexed files that no longer exist.

  Config -> CodeIndex -> IO [String]"
  [cfg index]
  (let [indexed (:files index)
        current (source-files cfg (:root index))
        changed-or-added (remove #(= (get-in indexed [% :mtime])
                                     (.lastModified (io/file ^String %)))
                                 current)
        deleted (remove (set current) (keys indexed))]
    (vec (concat changed-or-added deleted))))

(defn refresh
  "Brings `index` up to date with the files on disk, re-analysing only the
   stale files (see `stale-files`). Returns `index` itself when nothing
   changed. Precise tools call this before every answer.

  Config -> CodeIndex -> IO CodeIndex"
  [cfg index]
  (let [stale (stale-files cfg index)]
    (if (seq stale)
      (update-files index stale)
      index)))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(defn root
  "Absolute path of the project root, without a trailing slash.

  CodeIndex -> String"
  [index]
  (:root index))

(defn files
  "Absolute paths of all indexed files.

  CodeIndex -> [String]"
  [index]
  (keys (:files index)))

(defn file-mtimes
  "The modification time each indexed file was indexed at, by absolute path.

  CodeIndex -> {String Long}"
  [index]
  (update-vals (:files index) :mtime))

(defn select-files
  "Restricts `index` to the files `file-paths` (absolute).

  CodeIndex -> [String] -> CodeIndex"
  [index file-paths]
  (update index :files select-keys file-paths))

(defn file-lines
  "Reads the source lines of the file `file-path` (absolute).

  String -> IO [String]"
  [^String file-path]
  (with-open [reader (io/reader file-path)]
    (vec (line-seq reader))))

(defn form-lines
  "Cuts the lines of the form spanning `row:col` to `end-row:end-col`
   (1-based, `end-col` exclusive) out of a file's `lines`; nil when the
   range lies outside them.

  [String] -> FormRange -> Maybe [String]"
  [lines {:keys [row col end-row end-col]}]
  (let [form (->> lines (drop (dec row)) (take (inc (- end-row row))) vec)
        last-index (dec (count form))]
    (when (seq form)
      (-> form
          (update last-index #(subs % 0 (min (count %) (dec end-col))))
          (update 0 #(subs % (min (count %) (dec col))))))))

(defn test-file?
  "True when `file-path` (absolute) lies under a Test path or, when the
   project declares none (fallback), is named like a test file.

  CodeIndex -> String -> Boolean"
  [index ^String file-path]
  (boolean
   (or (some #(st/starts-with? file-path (str % "/")) (:test-paths index))
       (and (:test-names? index) (re-find test-file-pattern file-path)))))

(defn entries
  "All clj-kondo analysis entries of kind `entry-key` (such as
   `:var-usages`) across the indexed files.

  CodeIndex -> Keyword -> [AnalysisEntry]"
  [index entry-key]
  (mapcat (comp entry-key :analysis) (vals (:files index))))

(defn file-entries
  "The clj-kondo analysis entries of kind `entry-key` in one indexed file
   (`file-path` absolute).

  CodeIndex -> String -> Keyword -> [AnalysisEntry]"
  [index file-path entry-key]
  (get-in index [:files file-path :analysis entry-key]))

;; ---------------------------------------------------------------------------
;; Namespaces
;; ---------------------------------------------------------------------------

(defn namespace-names
  "Names of all namespaces defined in the project.

  CodeIndex -> [String]"
  [index]
  (->> (entries index :namespace-definitions)
       (map (comp str :name))
       distinct))

(defn namespace-definition
  "The clj-kondo namespace definition entry of `ns-str`, or nil.

  When several files define the namespace, the first by file and row wins.

  CodeIndex -> String -> Maybe AnalysisEntry"
  [index ns-str]
  (->> (entries index :namespace-definitions)
       (filter (comp #{ns-str} str :name))
       (sort-by (juxt :filename :row))
       first))

(defn requiring-namespaces
  "The namespaces that require `ns-sym`, sorted by name; nil when there are
   none.

  CodeIndex -> Symbol -> Maybe [Symbol]"
  [index ns-sym]
  (->> (entries index :namespace-usages)
       (filter (comp #{ns-sym} :to))
       (map :from)
       (remove #{ns-sym})
       (into (sorted-set-by #(compare (str %1) (str %2))))
       seq))

;; ---------------------------------------------------------------------------
;; Implementations
;; ---------------------------------------------------------------------------

(defn protocol-impls
  "The clj-kondo `:protocol-impls` entries of the protocol
   `protocol-ns`/`protocol-name`.

  CodeIndex -> Symbol -> Symbol -> [AnalysisEntry]"
  [index protocol-ns protocol-name]
  (filter #(and (= protocol-ns (:protocol-ns %)) (= protocol-name (:protocol-name %)))
          (entries index :protocol-impls)))

(defn method-heads
  "The clj-kondo usages of the multimethod `multi-ns`/`multi-name` that head
   a `defmethod` (they carry `:dispatch-val-str`).

  CodeIndex -> Symbol -> Symbol -> [AnalysisEntry]"
  [index multi-ns multi-name]
  (filter #(and (:defmethod %) (= multi-ns (:to %)) (= multi-name (:name %)))
          (entries index :var-usages)))

;; ---------------------------------------------------------------------------
;; Definitions of the project
;; ---------------------------------------------------------------------------

(defn definitions
  "All Definitions of the project: vars, tests, multimethods and their
   methods, protocols, records, specs and forms of project macros.

  CodeIndex -> [Definition]"
  [index]
  (mapcat :definitions (vals (:files index))))

(defn enclosing-definition
  "The Enclosing definition of the position `row`:`col` (1-based) in
   `file-path` (absolute): the innermost Definition whose form contains it,
   or nil. Works where clj-kondo gives no `:from-var` (defmethod, s/def,
   record and type bodies).

  CodeIndex -> String -> Long -> Long -> Maybe Definition"
  [index file-path row col]
  (->> (get-in index [:files file-path :definitions])
       (filter #(contains-position? % [row col]))
       ;; innermost first: latest start, then earliest end; of forms sharing
       ;; one range (`Circle`, `->Circle`) the shortest name
       (sort-by (fn [{def-row :row def-col :col :keys [end-row end-col] def-name :name}]
                  [(- def-row) (- def-col) end-row end-col (count (str def-name))]))
       first))

(defn- name-ns
  "The namespace that qualifies a Definition's name: its multimethod's for
   a defmethod, else its own.

  Definition -> Maybe Symbol"
  [{:keys [ns multimethod-ns]}]
  (or multimethod-ns ns))

(defn qualified-name
  "`ns/name` of a Definition (for a defmethod, its multimethod's `ns/name`
   followed by the dispatch value), the keyword of a spec, or the namespace
   name of a namespace Definition.

  Definition -> String"
  [{:keys [dispatch] def-name :name :as definition}]
  (cond (keyword? def-name) (str def-name)
        (name-ns definition) (cond-> (str (name-ns definition) "/" def-name)
                               dispatch (str " " dispatch))
        :else (str def-name)))

(defn local-name
  "Name of a Definition as written in its own namespace: `name`, followed
   by the dispatch value for a defmethod, whose multimethod is qualified
   when it lives in another namespace.

  Definition -> String"
  [{:keys [ns dispatch] def-name :name :as definition}]
  (cond-> (if (= ns (name-ns definition))
            (str def-name)
            (str (name-ns definition) "/" def-name))
    dispatch (str " " dispatch)))

;; ---------------------------------------------------------------------------
;; Name resolution
;; ---------------------------------------------------------------------------

(defn- alias-targets
  "Namespaces that `alias-str` stands for in any namespace of the project.

  CodeIndex -> String -> #{Symbol}"
  [index alias-str]
  (->> (entries index :namespace-usages)
       (filter (comp #{alias-str} str :alias))
       (into #{} (map :to))))

(defn split-name
  "Splits `name-str` into its namespace part and its simple name.

  `a/b` gives [\"a\" \"b\"]; the namespace part is nil for a simple name.

  String -> [Maybe String, String]"
  [name-str]
  (let [slash (st/index-of name-str "/")]
    (if (and slash (pos? slash) (< slash (dec (count name-str))))
      [(subs name-str 0 slash) (subs name-str (inc slash))]
      [nil name-str])))

(defn- normalize-dispatch
  "A dispatch value as written, with each run of whitespace as one space.

  Maybe String -> Maybe String"
  [dispatch-str]
  (some-> dispatch-str (st/replace #"\s+" " ")))

(defn- symbol-matcher
  "A predicate on Definitions for `name-str`, optionally followed by a
   defmethod dispatch value (`area :circle`).

  CodeIndex -> String -> (Definition -> Boolean)"
  [index name-str]
  (let [[head dispatch] (st/split name-str #"\s+" 2)
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
  "A predicate on spec Definitions for a keyword as written: `:app.core/uuid`,
   `::uuid` (in any namespace) or `::c/uuid` (alias-qualified).

  CodeIndex -> String -> (Definition -> Boolean)"
  [index keyword-str]
  (let [auto-resolved? (st/starts-with? keyword-str "::")
        [ns-part simple] (split-name (st/replace keyword-str #"^::?" ""))
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
   denotes its spec. More than one result means the name is ambiguous; nil
   for a blank name.

  CodeIndex -> Maybe String -> Maybe [Definition]"
  [index name-str]
  (let [name-str (st/trim (or name-str ""))]
    (when-not (or (st/blank? name-str) (= ":" name-str) (= "::" name-str))
      (->> (definitions index)
           (filter (if (st/starts-with? name-str ":")
                     (keyword-matcher index name-str)
                     (symbol-matcher index name-str)))
           (sort-by (juxt :filename :row))
           vec))))
