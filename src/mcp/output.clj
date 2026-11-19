(ns mcp.output
  "Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
  (:require [clojure.string :as s]))

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
  (let [prefix (str (s/replace (or root-path "") #"/+$" "") "/")]
    (if (s/starts-with? (str file-path) prefix)
      (subs file-path (count prefix))
      file-path)))

;; ---------------------------------------------------------------------------
;; Text
;; ---------------------------------------------------------------------------

(defn first-line
  "First non-blank line of `text` (e.g. a docstring), trimmed; nil if none."
  [text]
  (some->> (some-> text s/split-lines)
           (map s/trim)
           (remove s/blank?)
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
      true (->> (s/join "\n")))))

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
