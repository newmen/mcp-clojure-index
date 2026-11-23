(ns token-benchmark
  "Measures how many characters an agent receives answering navigation
   questions with this server versus `rg` plus reading files (ADR-0009).

   Run from the repository root:

     lein run -m clojure.main -i dev/token_benchmark.clj \\
       -e '(token-benchmark/-main \"PROJECT-ROOT\" \"QUERIES-EDN\" \"SKIP-REGEX\")'

   QUERIES-EDN holds the semantic search scenarios of that project, a vector
   of SemanticScenario; pass \"\" to skip the
   semantic scenarios. SKIP-REGEX (optional) matches files no question is
   asked about, such as generated code; grep still searches them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.semantic-index :as semantic-index]
            [mcp.tool :as tool]
            [mcp.tools]))

(set! *warn-on-reflection* true)

;; Measurement (one question):
;;
;; {:scenario :find-usages
;;  :question "mcp.output/relative-path"
;;  :mcp      930            ; characters of every answer page the agent needs
;;  :careful  12000          ; rg output + windows of the files it must read
;;  :typical  180000         ; rg output + whole files, as Read returns them
;;  :mcp-found? true :grep-found? true          ; semantic scenarios only
;;  :grep-noise 3 :grep-missed 1}               ; find-usages only
;;
;; SemanticScenario (one entry of QUERIES-EDN):
;;
;; {:query  "shorten a reply that would be longer than the allowed number of characters"
;;  :grep   ["truncat" "max-chars"]      ; the keywords an agent greps instead
;;  :expect "mcp.output/render"}         ; the Definition that answers it
;;
;; ReportRow (one scenario of the summary): the totals of :mcp, :careful and
;; :typical, the total and median savings in percent, and the scenario's own
;; counters (:grep-noise and :grep-missed, or :mcp-found and :grep-found).

(s/def ::scenario #{:find-definition :get-source :find-usages :namespace-summary :semantic-search})

(s/def ::question string?)

(s/def ::mcp nat-int?)

(s/def ::careful nat-int?)

(s/def ::typical nat-int?)

(s/def ::measurement (s/keys :req-un [::scenario ::question ::mcp ::careful ::typical]))

(s/def ::query string?)

(s/def ::grep (s/coll-of string?))

(s/def ::expect string?)

(s/def ::semantic-scenario (s/keys :req-un [::query ::grep ::expect]))

(s/def ::questions nat-int?)

(s/def ::report-row (s/keys :req-un [::scenario ::questions ::mcp ::careful ::typical]))

(def ^:private sample-size
  "Definitions sampled per project for the precise scenarios.

  Long"
  20)

(def ^:private seed
  "Seed of the Definition sample, so a rerun asks the same questions.

  Long"
  42)

(def ^:private source-globs
  "The `rg` globs of the files the server indexes; grep is held to them so
   the baseline never pays for files the server does not read.

  [String]"
  ["-g" "*.clj" "-g" "*.cljc" "-g" "*.cljs" "-g" "*.edn"])

(def ^:private ^:dynamic *excluded*
  "The `:index/exclude` patterns of the measured project: grep skips the
   directories the server does not index either.

  [Pattern]"
  [])

(def ^:private usage-window
  "Lines read before and after a grep hit to see which Definition encloses
   it.

  Long"
  10)

(def ^:private head-window
  "Lines read from a definition line to see its arglists and docstring.

  Long"
  15)

(def ^:private body-overshoot
  "Lines read past the end of a body: the agent does not know where it ends.

  Long"
  10)

(def ^:private semantic-window
  "Lines read around each of the first grep hits of a semantic question.

  Long"
  15)

;; ---------------------------------------------------------------------------
;; What the agent receives without the server
;; ---------------------------------------------------------------------------

(defn- rg
  "The output of `rg -n` for `args` over `path` (the whole project by
   default) in `root`, as the Grep tool returns it.

  String -> [String] -> IO String
  String -> [String] -> String -> IO String"
  ([root args] (rg root args "."))
  ([root args path]
   (:out (apply shell/sh "rg" "-n" "--no-heading" "--color" "never"
                (concat source-globs
                        (mapcat #(vector "-g" (str "!**/" (st/replace (str %) "\\." ".") "/**"))
                                *excluded*)
                        args [path :dir root])))))

(defn- regex-quote
  "`text` with the regex metacharacters of `rg` escaped.

  String -> String"
  [text]
  (st/replace text #"[\\.?*+^$|()\[\]{}]" "\\\\$0"))

(defn- hits
  "The [relative-path row] of every line of `rg` output.

  String -> [[String Long]]"
  [rg-output]
  (for [line (st/split-lines rg-output)
        :let [[_line path row] (re-find #"^(?:\./)?([^:]+):(\d+):" line)]
        :when path]
    [path (parse-long row)]))

(defn- numbered-length
  "Characters of the lines `from`..`to` (1-based, inclusive) of `lines` in
   the Read tool's format: a right-aligned line number and a tab per line.

  [String] -> Long -> Long -> Long"
  [lines from to]
  (->> (range (max 1 from) (inc (min to (count lines))))
       (map #(+ 7 (count (nth lines (dec %))) 1))
       (reduce + 0)))

(defn- merge-windows
  "Overlapping or adjacent [from to] windows merged into disjoint ones.

  [[Long Long]] -> [[Long Long]]"
  [windows]
  (reduce (fn [acc [from to]]
            (let [[last-from last-to] (peek acc)]
              (if (and last-to (<= from (inc last-to)))
                (conj (pop acc) [last-from (max to last-to)])
                (conj acc [from to]))))
          []
          (sort windows)))

(defn- read-length
  "Characters the Read tool returns for `windows` ([from to] lines, nil for
   the whole file) of the file `relative-path` under `root`.

  String -> String -> Maybe [[Long Long]] -> IO Long"
  [root relative-path windows]
  (let [lines (code-index/file-lines (str root "/" relative-path))]
    (if windows
      (->> (merge-windows windows)
           (map (fn [[from to]] (numbered-length lines from to)))
           (reduce + 0))
      (numbered-length lines 1 (count lines)))))

(defn- reads-length
  "Characters of reading, per file of `hit-list`, the windows `window-of`
   gives a hit ([from to]), or whole files when `window-of` is nil.

  String -> [[String Long]] -> Maybe ([String Long] -> [Long Long]) -> IO Long"
  [root hit-list window-of]
  (->> (group-by first hit-list)
       (map (fn [[relative-path file-hits]]
              (read-length root relative-path (when window-of (map window-of file-hits)))))
       (reduce + 0)))

;; ---------------------------------------------------------------------------
;; What the agent receives from the server
;; ---------------------------------------------------------------------------

(defn- answer-text
  "The text of one tool answer.

  Keyword -> ToolArgs -> ToolState -> IO String"
  [tool-key args state]
  (->> (tool/call tool-key args state) :content (map :text) (st/join "\n")))

(defn- all-pages
  "The texts of every page of a tool answer, following Truncation notes.

  Keyword -> ToolArgs -> ToolState -> IO [String]"
  [tool-key args state]
  (loop [offset 0 pages []]
    (let [text (answer-text tool-key (assoc args :offset offset) state)
          next-offset (some-> (re-find #"pass offset=(\d+)" text) second parse-long)
          pages (conj pages text)]
      (if (and next-offset (> (long next-offset) offset) (< (count pages) 50))
        (recur (long next-offset) pages)
        pages))))

(defn- pages-length
  "Characters of every page of a tool answer.

  Keyword -> ToolArgs -> ToolState -> IO Long"
  [tool-key args state]
  (reduce + 0 (map count (all-pages tool-key args state))))

;; ---------------------------------------------------------------------------
;; Scenarios
;; ---------------------------------------------------------------------------

(defn- relative
  "`file-path` relative to `root`.

  String -> String -> String"
  [root file-path]
  (subs file-path (inc (count root))))

(defn- definition-pattern
  "The `rg` pattern a careful agent writes for the definition of `def-name`.

  String -> String"
  [def-name]
  (str "\\(def[a-z\\-]*\\s+(\\^\\S+\\s+)*" (regex-quote def-name) "(\\s|$)"))

(defn- usage-pattern
  "The `rg` pattern a careful agent writes for the Usages of `def-name`:
   the name as a whole symbol, alias-qualified or not.

  String -> String"
  [def-name]
  (str "(^|[\\s(\\[{#'@~^/])" (regex-quote def-name) "($|[\\s)\\]}])"))

(defn- find-definition-measure
  "Measures `find_definition` of a Definition's simple name.

  ToolState -> Definition -> IO Measurement"
  [{:keys [code-index] :as state} definition]
  (let [root (code-index/root code-index)
        def-name (str (:name definition))
        output (rg root ["-e" (definition-pattern def-name)])
        found (hits output)]
    {:scenario :find-definition
     :question def-name
     :mcp (pages-length :find_definition {:name def-name} state)
     :careful (+ (count output) (reads-length root found (fn [[_path row]] [row (+ row head-window -1)])))
     :typical (+ (count output) (reads-length root found nil))}))

(defn- get-source-measure
  "Measures `get_source` of a Definition by its qualified name.

  ToolState -> Definition -> IO Measurement"
  [{:keys [code-index] :as state} {:keys [filename row end-row] :as definition}]
  (let [root (code-index/root code-index)
        output (rg root ["-e" (definition-pattern (str (:name definition)))])
        path (relative root filename)]
    {:scenario :get-source
     :question (code-index/qualified-name definition)
     :mcp (pages-length :get_source {:name (code-index/qualified-name definition)} state)
     :careful (+ (count output) (read-length root path [[row (+ end-row body-overshoot)]]))
     :typical (+ (count output) (read-length root path nil))}))

(defn- mcp-usage-hits
  "The [relative-path row] of every Usage line in `find_usages` pages.

  [String] -> #{[String Long]}"
  [pages]
  (set (for [page pages
             line (st/split-lines page)
             :let [[_line path row] (re-find #"^(\S+):(\d+):\d+\s" line)]
             :when path]
         [path (parse-long row)])))

(defn- find-usages-measure
  "Measures `find_usages` of a Definition, with how many careful grep hits
   are not Usages (noise) and how many Usages grep misses.

  ToolState -> Definition -> IO Measurement"
  [{:keys [code-index] :as state} {:keys [filename row] :as definition}]
  (let [root (code-index/root code-index)
        def-name (str (:name definition))
        pages (all-pages :find_usages {:targets [(code-index/qualified-name definition)]} state)
        careful-output (rg root ["-e" (usage-pattern def-name)])
        typical-output (rg root ["-F" "-e" def-name])
        careful-hits (set (hits careful-output))
        usages (mcp-usage-hits pages)
        definition-hit [(relative root filename) row]]
    {:scenario :find-usages
     :question (code-index/qualified-name definition)
     :mcp (reduce + 0 (map count pages))
     :careful (+ (count careful-output)
                 (reads-length root (seq careful-hits)
                               (fn [[_path hit-row]] [(- hit-row usage-window) (+ hit-row usage-window)])))
     :typical (+ (count typical-output) (reads-length root (hits typical-output) nil))
     :grep-noise (count (remove (conj usages definition-hit) careful-hits))
     :grep-missed (count (remove careful-hits usages))}))

(defn- namespace-summary-measure
  "Measures `namespace_summary` of the namespace defined in `file-path`.

  ToolState -> [String String] -> IO Measurement"
  [{:keys [code-index] :as state} [ns-str file-path]]
  (let [root (code-index/root code-index)
        path (relative root file-path)
        definitions (rg root ["-e" "^\\s*\\(def"] path)
        requirers (rg root ["-F" "-e" ns-str])]
    {:scenario :namespace-summary
     :question ns-str
     :mcp (pages-length :namespace_summary {:ns ns-str} state)
     :careful (+ (count definitions) (count requirers))
     :typical (+ (read-length root path nil) (count requirers))}))

(defn- top-files
  "The `file-count` files with the most hits in `hit-list`.

  Long -> [[String Long]] -> [[String Long]]"
  [file-count hit-list]
  (let [top (->> (frequencies (map first hit-list))
                 (sort-by (comp - val))
                 (take file-count)
                 (map key)
                 set)]
    (filter (comp top first) hit-list)))

(defn- semantic-search-measure
  "Measures `semantic_search` of one query against grepping its keywords,
   with whether either finds the expected Definition.

  ToolState -> SemanticScenario -> IO Measurement"
  [{:keys [code-index] :as state} {:keys [query grep expect]}]
  (let [root (code-index/root code-index)
        answer (answer-text :semantic_search {:query query} state)
        output (rg root (into ["-i"] (mapcat (partial vector "-e") grep)))
        hit-list (hits output)
        target (first (filter (comp #{expect} code-index/qualified-name)
                              (code-index/definitions code-index)))]
    {:scenario :semantic-search
     :question query
     :mcp (count answer)
     :careful (+ (count output)
                 (reads-length root (take 5 hit-list)
                               (fn [[_path row]] [(- row semantic-window) (+ row semantic-window)])))
     :typical (+ (count output) (reads-length root (top-files 3 hit-list) nil))
     :mcp-found? (st/includes? answer (str "  " expect "  "))
     :grep-found? (boolean
                   (and target
                        (some (fn [[path row]]
                                (and (= path (relative root (:filename target)))
                                     (<= (:row target) row (:end-row target))))
                              hit-list)))}))

;; ---------------------------------------------------------------------------
;; Sample and report
;; ---------------------------------------------------------------------------

(defn- asked-about?
  "True when questions may be asked about the Definition or namespace of
   `file-path`: a source (not test) file not matched by `skip-files`.

  CodeIndex -> Maybe Pattern -> String -> Boolean"
  [index skip-files file-path]
  (not (or (code-index/test-file? index file-path)
           (and skip-files (re-find skip-files file-path)))))

(defn- sample
  "`sample-count` functions and macros of `index` that questions may be asked about,
   the same ones on every run over unchanged code.

  CodeIndex -> Maybe Pattern -> Long -> [Definition]"
  [index skip-files sample-count]
  (let [candidates (->> (code-index/definitions index)
                        (filter (comp #{"defn" "defn-" "defmacro"} :kind))
                        (filter (comp (partial asked-about? index skip-files) :filename))
                        (sort-by code-index/qualified-name))
        candidates (java.util.ArrayList. ^java.util.Collection candidates)]
    (java.util.Collections/shuffle candidates (java.util.Random. seed))
    (take sample-count candidates)))

(defn- namespaces
  "[namespace-name file-path] of every namespace questions may be asked
   about.

  CodeIndex -> Maybe Pattern -> [[String String]]"
  [index skip-files]
  (->> (code-index/entries index :namespace-definitions)
       (filter (comp (partial asked-about? index skip-files) :filename))
       (map (juxt (comp str :name) :filename))
       (sort-by first)))

(defn- saving
  "The share of `baseline` characters the server saves, in percent.

  Long -> Long -> Long"
  [mcp baseline]
  (if (pos? baseline) (Math/round (* 100.0 (- 1.0 (/ (double mcp) baseline)))) 0))

(defn- median
  "The median of `numbers`.

  [Long] -> Long"
  [numbers]
  (let [sorted (vec (sort numbers))]
    (nth sorted (quot (count sorted) 2))))

(defn- summarize
  "One report row per scenario of `measurements`.

  [Measurement] -> [ReportRow]"
  [measurements]
  (for [[scenario group] (group-by :scenario measurements)
        :let [total (fn [k] (reduce + 0 (map k group)))]]
    (cond-> {:scenario scenario
             :questions (count group)
             :mcp (total :mcp)
             :careful (total :careful)
             :typical (total :typical)
             :saving-careful (saving (total :mcp) (total :careful))
             :saving-typical (saving (total :mcp) (total :typical))
             :median-careful (median (map #(saving (:mcp %) (:careful %)) group))
             :median-typical (median (map #(saving (:mcp %) (:typical %)) group))
             :mcp-bigger (count (filter #(> (:mcp %) (:careful %)) group))}
      (= scenario :find-usages)
      (assoc :grep-noise (total :grep-noise) :grep-missed (total :grep-missed))
      (= scenario :semantic-search)
      (assoc :mcp-found (count (filter :mcp-found? group))
             :grep-found (count (filter :grep-found? group))))))

(defn run
  "Measures every scenario on the project at `root-path`; `queries` are its
   semantic search scenarios (may be empty), `skip-files` matches files no
   question is asked about (generated code), though grep still reads them.

  String -> [SemanticScenario] -> Maybe Pattern -> IO [Measurement]"
  [root-path queries skip-files]
  (let [cfg (-> (config/load-config "resources/config.edn")
                (assoc :index/root-path root-path)
                (dissoc :qdrant/collection)
                config/resolve-config
                config/validate-config)
        index (code-index/build cfg)
        semantic (semantic-index/create cfg)
        state {:config cfg :code-index index :semantic-index semantic}
        definitions (sample index skip-files sample-size)]
    (when (seq queries)
      (semantic-index/sync! semantic index))
    (binding [*excluded* (:index/exclude cfg)]
      (vec (concat (map (partial find-definition-measure state) definitions)
                   (map (partial get-source-measure state) definitions)
                   (map (partial find-usages-measure state) definitions)
                   (map (partial namespace-summary-measure state) (namespaces index skip-files))
                   (map (partial semantic-search-measure state) queries))))))

(defn -main
  "Prints the per-question measurements and the summary of one project.

  String -> IO ()
  String -> String -> IO ()
  String -> String -> String -> IO ()"
  [root-path & [queries-path skip-regex]]
  (let [queries (if (st/blank? queries-path) [] (edn/read-string (slurp (io/file ^String queries-path))))
        measurements (run root-path queries (when-not (st/blank? skip-regex) (re-pattern skip-regex)))]
    (doseq [measurement measurements]
      (prn measurement))
    (println)
    (doseq [row (summarize measurements)]
      (prn row))
    (shutdown-agents)))
