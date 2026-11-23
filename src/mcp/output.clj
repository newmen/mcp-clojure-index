(ns mcp.output
  "Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]))

(set! *warn-on-reflection* true)

;; AnswerFrame: the optional header and footer line around the findings of
;; an answer, {:header "..." :footer "..."}; either may be absent or nil.

(s/def ::header (s/nilable string?))

(s/def ::footer (s/nilable string?))

(s/def ::answer-frame (s/keys :opt-un [::header ::footer]))

;; OutputBudget: the Output budget of one call, {:limit :max-chars :offset}.
;; A page shows at most :limit findings starting at finding :offset, in at
;; most :max-chars characters.

(s/def ::output-budget (s/keys :req-un [::limit ::max-chars ::offset]))

(defrecord OutputBudget [limit max-chars offset])

;; ---------------------------------------------------------------------------
;; Budget
;; ---------------------------------------------------------------------------

(defn budget
  "The Output budget of a call with the tool's `args`.

  `:limit` of the args lowers `:output/limit` of the config, never raises
  it; `:offset` defaults to 0.

  Config -> ToolArgs -> OutputBudget"
  [cfg {:keys [limit offset]}]
  (let [ceiling (:output/limit cfg)]
    (->OutputBudget (min ceiling (or limit ceiling))
                    (:output/max-chars cfg)
                    (max 0 (or offset 0)))))

(defn- truncation-note
  "The Truncation note of a page.

  The page ends at finding `shown-until` of `total`.

  Long -> Long -> String"
  [shown-until total]
  (str "truncated: shown " shown-until " of " total
       ", pass offset=" shown-until " or narrow the query"))

;; ---------------------------------------------------------------------------
;; Paths
;; ---------------------------------------------------------------------------

(defn relative-path
  "Returns `file-path` relative to the project root `root-path`; paths
   outside the root are returned unchanged.

  Maybe String -> String -> String"
  [root-path file-path]
  (let [prefix (str (st/replace (or root-path "") #"/+$" "") "/")]
    (if (st/starts-with? (str file-path) prefix)
      (subs file-path (count prefix))
      file-path)))

;; ---------------------------------------------------------------------------
;; Text
;; ---------------------------------------------------------------------------

(defn first-line
  "The first non-blank line of `text`, trimmed; nil if none.

  Maybe String -> Maybe String"
  [text]
  (some->> (some-> text st/split-lines)
           (map st/trim)
           (remove st/blank?)
           first))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- answer-text
  "Joins the `shown` findings of a page starting at `offset`, adding a
   Truncation note when findings remain after them.

  [String] -> Long -> Long -> String"
  [shown offset total]
  (let [shown-until (+ offset (count shown))]
    (cond-> (vec shown)
      (< shown-until total) (conj (truncation-note shown-until total))
      true (->> (st/join "\n")))))

(defn- oversized-first-finding
  "Cuts the first finding of a page that alone exceeds `max-chars`, so
   that paging still advances by one.

  Long -> [String] -> Long -> Long -> String"
  [max-chars page offset total]
  (if-let [finding (first page)]
    (let [note-chars (count (answer-text [] (inc offset) total))
          room (max 0 (- max-chars note-chars 2))
          cut (str (subs finding 0 (min room (count finding))) "…")]
      (answer-text [cut] offset total))
    ""))

(defn render
  "Renders `findings` (one string per finding) as an answer within the
   Output budget.

  OutputBudget -> [String] -> String"
  [{:keys [limit max-chars offset]} findings]
  (let [total (count findings)
        page (->> findings (drop offset) (take limit))
        answers (->> (range (count page) 0 -1)
                     (map #(answer-text (take % page) offset total)))]
    (or (first (filter #(<= (count %) max-chars) answers))
        (oversized-first-finding max-chars page offset total))))

(defn render-framed
  "Renders `findings` inside an AnswerFrame.

  The optional `header` and `footer` lines go around the findings, the
  whole answer still within the Output budget.

  OutputBudget -> AnswerFrame -> [String] -> String"
  [budget {:keys [header footer]} findings]
  (let [frame-chars (+ (if header (inc (count header)) 0)
                       (if footer (inc (count footer)) 0))
        body (render (update budget :max-chars - frame-chars) findings)]
    (->> [header body footer]
         (remove st/blank?)
         (st/join "\n"))))

;; ---------------------------------------------------------------------------
;; Shared answers
;; ---------------------------------------------------------------------------

(defn checked
  "What an answer searched: `(checked N files, including tests)`, or
   excluding tests (which are then not counted) when `include-tests?` is
   false.

  CodeIndex -> String
  CodeIndex -> Boolean -> String"
  ([index] (checked index true))
  ([index include-tests?]
   (let [searched (cond->> (code-index/files index)
                    (not include-tests?) (remove (partial code-index/test-file? index)))]
     (str "(checked " (count searched) " files, "
          (if include-tests? "including" "excluding") " tests)"))))

(defn not-found
  "The answer for a name that resolves to nothing.

  It says what was searched.

  CodeIndex -> String -> String"
  [index name-str]
  (str "not found: " name-str " " (checked index)))

(defn definition->line
  "The Compact answer line of a Definition.

  The line is `path:line  kind  ns/name  [arglists]  first docstring line`.

  CodeIndex -> Definition -> String"
  [index {:keys [filename row kind arglists doc] :as definition}]
  (->> [(str (relative-path (code-index/root index) filename) ":" row)
        kind
        (code-index/qualified-name definition)
        (st/join " " arglists)
        (first-line doc)]
       (remove st/blank?)
       (st/join "  ")))

(defn ambiguous-header
  "The header line for `name-str` that denotes `candidate-count`
   candidates, naming what they are (`what`, such as \"definitions\").

  String -> String -> Long -> String"
  [what name-str candidate-count]
  (str "ambiguous: " candidate-count " " what " named " name-str ", pass a qualified name"))

(defn ambiguous
  "The answer for `name-str` that denotes several `definitions`: an
   `ambiguous-header`, then one line per candidate within the Output budget.

  OutputBudget -> CodeIndex -> String -> String -> [Definition] -> String"
  [budget index what name-str definitions]
  (render-framed budget
                 {:header (ambiguous-header what name-str (count definitions))}
                 (mapv (partial definition->line index) definitions)))

(defn one-definition
  "The answer for `name-str` that resolved to `definitions`: `not-found`
   for none, `(answer-fn definition)` for exactly one, the `ambiguous`
   candidates otherwise.

  OutputBudget -> CodeIndex -> String -> [Definition] -> (Definition -> IO String) -> IO String"
  [budget index name-str definitions answer-fn]
  (case (count definitions)
    0 (not-found index name-str)
    1 (answer-fn (first definitions))
    (ambiguous budget index "definitions" name-str definitions)))
