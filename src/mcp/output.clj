(ns mcp.output
  "Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
  (:require [clojure.string :as st]
            [mcp.code-index :as code-index]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Budget
;; ---------------------------------------------------------------------------

(defn- effective-limit
  [cfg args]
  (let [ceiling (:output/limit cfg)]
    (min ceiling (or (:limit args) ceiling))))

(defn- truncation-note
  [shown-until total]
  (str "truncated: shown " shown-until " of " total
       ", pass offset=" shown-until " or narrow the query"))

;; ---------------------------------------------------------------------------
;; Paths
;; ---------------------------------------------------------------------------

(defn relative-path
  "Return `file-path` relative to the project root `root-path`; paths
   outside the root are returned unchanged."
  [root-path file-path]
  (let [prefix (str (st/replace (or root-path "") #"/+$" "") "/")]
    (if (st/starts-with? (str file-path) prefix)
      (subs file-path (count prefix))
      file-path)))

;; ---------------------------------------------------------------------------
;; Text
;; ---------------------------------------------------------------------------

(defn first-line
  "First non-blank line of `text` (e.g. a docstring), trimmed; nil if none."
  [text]
  (some->> (some-> text st/split-lines)
           (map st/trim)
           (remove st/blank?)
           first))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- answer-text
  "Join the `shown` findings of a page starting at `offset`, adding a
   Truncation note when findings remain after them."
  [shown offset total]
  (let [shown-until (+ offset (count shown))]
    (cond-> (vec shown)
      (< shown-until total) (conj (truncation-note shown-until total))
      true (->> (st/join "\n")))))

(defn- oversized-first-finding
  "Answer for a page whose first finding alone exceeds `:output/max-chars`:
   the finding is cut so that paging still advances by one."
  [cfg page offset total]
  (if-let [finding (first page)]
    (let [note-chars (count (answer-text [] (inc offset) total))
          room (max 0 (- (:output/max-chars cfg) note-chars 2))
          cut (str (subs finding 0 (min room (count finding))) "…")]
      (answer-text [cut] offset total))
    ""))

(defn render
  "Render `findings` (one string per finding) as an answer within the Output
   budget from `cfg`. `args` may carry the tool's `:limit` (lowers the
   ceiling only) and `:offset`."
  [cfg args findings]
  (let [total (count findings)
        offset (max 0 (or (:offset args) 0))
        page (->> findings (drop offset) (take (effective-limit cfg args)))
        answers (->> (range (count page) 0 -1)
                     (map #(answer-text (take % page) offset total)))]
    (or (first (filter #(<= (count %) (:output/max-chars cfg)) answers))
        (oversized-first-finding cfg page offset total))))

(defn render-framed
  "`render` `findings` between an optional `header` line and an optional
   `footer` line, the whole answer still within `:output/max-chars`."
  [cfg args {:keys [header footer]} findings]
  (let [frame-chars (+ (if header (inc (count header)) 0)
                       (if footer (inc (count footer)) 0))
        body (render (update cfg :output/max-chars - frame-chars) args findings)]
    (->> [header body footer]
         (remove st/blank?)
         (st/join "\n"))))

;; ---------------------------------------------------------------------------
;; Shared answers
;; ---------------------------------------------------------------------------

(defn checked
  "What an answer searched: `(checked N files, including tests)`, or
   excluding tests (which are then not counted) when `include-tests?` is
   false."
  ([index] (checked index true))
  ([index include-tests?]
   (let [searched (cond->> (code-index/files index)
                    (not include-tests?) (remove (partial code-index/test-file? index)))]
     (str "(checked " (count searched) " files, "
          (if include-tests? "including" "excluding") " tests)"))))

(defn not-found
  "Answer for `name-str` that resolves to nothing, saying what was searched."
  [index name-str]
  (str "not found: " name-str " " (checked index)))

(defn definition->line
  "Compact answer line of a Definition:
   `path:line  kind  ns/name  [arglists]  first docstring line`."
  [index {:keys [filename row kind arglists doc] :as definition}]
  (->> [(str (relative-path (code-index/root index) filename) ":" row)
        kind
        (code-index/qualified-name definition)
        (st/join " " arglists)
        (first-line doc)]
       (remove st/blank?)
       (st/join "  ")))

(defn ambiguous-header
  "Header line for `name-str` that denotes `candidate-count` candidates,
   naming what they are (`what`, e.g. \"definitions\")."
  [what name-str candidate-count]
  (str "ambiguous: " candidate-count " " what " named " name-str ", pass a qualified name"))

(defn ambiguous
  "Answer for `name-str` that denotes several `definitions`: an
   `ambiguous-header`, then one line per candidate within the Output budget."
  [cfg args index what name-str definitions]
  (render-framed cfg args
                 {:header (ambiguous-header what name-str (count definitions))}
                 (mapv (partial definition->line index) definitions)))

(defn one-definition
  "Answer for `name-str` that resolved to `definitions`: `not-found` for
   none, `(answer-fn definition)` for exactly one, the `ambiguous`
   candidates otherwise."
  [cfg args index name-str definitions answer-fn]
  (case (count definitions)
    0 (not-found index name-str)
    1 (answer-fn (first definitions))
    (ambiguous cfg args index "definitions" name-str definitions)))
