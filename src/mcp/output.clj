(ns mcp.output
  "Output budget (ADR-0004) and Compact answer helpers (ADR-0006)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]
            [mcp.config :as config]
            [mcp.docstring :as docstring]
            [mcp.text :as text]))

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
  (let [ceiling (config/setting cfg :output/limit)]
    (->OutputBudget (min ceiling (or limit ceiling))
                    (config/setting cfg :output/max-chars)
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
  "Cuts the first finding of a page that alone exceeds `max-chars` with
   `cut`, so that paging still advances by one.

  (Long -> a -> String) -> Long -> [a] -> Long -> Long -> String"
  [cut max-chars page offset total]
  (if-let [finding (first page)]
    (let [note-chars (count (answer-text [] (inc offset) total))
          room (max 0 (- max-chars note-chars (if (pos? note-chars) 1 0)))]
      (answer-text [(cut room finding)] offset total))
    ""))

;; RenderOptions: how `render` shows the findings,
;; {:text-of (fn [finding] ...) :cut (fn [room finding] ...)}. `:text-of`
;; is the text of a finding (default: the finding is a string); `:cut` is
;; a finding that alone exceeds the Output budget, cut to at most `room`
;; characters (default: its text ends with `…`).

(s/def ::text-of fn?)

(s/def ::cut fn?)

(s/def ::render-options (s/keys :opt-un [::text-of ::cut]))

(defn render
  "Renders `findings` as an answer within the Output budget.

  OutputBudget -> [String] -> String
  OutputBudget -> [a] -> RenderOptions -> String"
  ([budget findings] (render budget findings {}))
  ([{:keys [limit max-chars offset]} findings {:keys [text-of cut] :or {text-of identity}}]
   (let [total (count findings)
         page (->> findings (drop offset) (take limit))
         texts (map text-of page)
         answers (->> (range (count page) 0 -1)
                      (map #(answer-text (take % texts) offset total)))]
     (or (first (filter #(<= (count %) max-chars) answers))
         (oversized-first-finding (or cut #(text/ellipsis-cut %1 (text-of %2)))
                                  max-chars page offset total)))))

(defn render-framed
  "Renders `findings` inside an AnswerFrame.

  The optional `header` and `footer` lines go around the findings, the
  whole answer still within the Output budget.

  OutputBudget -> AnswerFrame -> [String] -> String
  OutputBudget -> AnswerFrame -> [a] -> RenderOptions -> String"
  ([budget frame findings] (render-framed budget frame findings {}))
  ([budget {:keys [header footer]} findings options]
   (let [frame-chars (+ (if header (inc (count header)) 0)
                        (if footer (inc (count footer)) 0))
         body (render (update budget :max-chars - frame-chars) findings options)]
     (->> [header body footer]
          (remove st/blank?)
          (st/join "\n")))))

;; AnswerGroup: the answer for one name or target of a batch call,
;; {:header "app.core/get-uuid: 11 usages" :findings [...]}; the header
;; line heads the group's findings. A group without findings is its
;; header alone; it takes no place in the page and shows with the finding
;; after it (or, at the end, the one before it).

(s/def ::findings sequential?)

(s/def ::answer-group (s/keys :req-un [::header] :opt-un [::findings]))

;; GroupedFinding: one finding of `render-grouped`, with the header lines
;; shown around it, {:prefix String :finding a :suffix String};
;; `:finding` is absent when no group has findings and the entry is the
;; headers alone.

(s/def ::prefix string?)

(s/def ::suffix string?)

(s/def ::grouped-finding (s/keys :req-un [::prefix] :opt-un [::finding ::suffix]))

(defn- header-lines
  "The `headers` as the lines before a finding, each ending with a line
   break and after a blank line, except the first one when it opens the
   page.

  [String] -> Boolean -> String"
  [headers opens-page?]
  (->> headers
       (map-indexed (fn [header-index header]
                      (str (when-not (and opens-page? (zero? header-index)) "\n")
                           header "\n")))
       (apply str)))

(defn- grouped-findings
  "The findings of `groups` as one sequence of GroupedFindings.

  The prefix of a group's first finding is its header line, after a blank
  line unless the finding opens the page at `offset`; the finding that
  opens the page repeats its group's header. The headers of groups
  without findings go before the next finding, those at the end after
  the last one.

  [AnswerGroup] -> Long -> [GroupedFinding]"
  [groups offset]
  (let [entries (->> groups
                     (mapcat (fn [{:keys [header findings]}]
                               (if (seq findings)
                                 (map-indexed (fn [finding-index finding]
                                                {:header header :first? (zero? finding-index) :finding finding})
                                              findings)
                                 [{:empty-header header}]))))
        {:keys [found pending]}
        (reduce (fn [{:keys [found pending]} {:keys [empty-header header first? finding]}]
                  (if empty-header
                    {:found found :pending (conj pending empty-header)}
                    (let [position (count found)
                          headers (cond-> pending
                                    (or first? (= position offset)) (conj header))]
                      {:found (conj found {:prefix (header-lines headers (= position offset))
                                           :finding finding})
                       :pending []})))
                {:found [] :pending []}
                entries)]
    (cond
      (empty? found) [{:prefix (st/join "\n\n" pending)}]
      (empty? pending) found
      :else (update found (dec (count found))
                    assoc :suffix (apply str (map (partial str "\n\n") pending))))))

(defn render-grouped
  "Renders the findings of `groups` as one answer within the Output
   budget, laid out like `rg --heading`: each group's header line, then
   its findings, a blank line between groups.

  The budget counts findings across groups, not the groups without
  findings; a page that starts inside a group repeats its header.
  `options` render one finding, as in `render`.

  OutputBudget -> [AnswerGroup] -> String
  OutputBudget -> [AnswerGroup] -> RenderOptions -> String"
  ([budget groups] (render-grouped budget groups {}))
  ([budget groups {:keys [text-of cut] :or {text-of identity}}]
   (let [cut (or cut #(text/ellipsis-cut %1 (text-of %2)))
         text (fn [{:keys [prefix suffix] :as entry}]
                (str prefix (when (contains? entry :finding) (text-of (:finding entry))) suffix))]
     (render budget (grouped-findings groups (:offset budget))
             {:text-of text
              :cut (fn [room {:keys [prefix suffix] :as entry}]
                     (if (contains? entry :finding)
                       (str prefix
                            (cut (max 0 (- room (count prefix) (count suffix))) (:finding entry))
                            suffix)
                       (text/ellipsis-cut room prefix)))}))))

;; FindingCut: what `cut-finding` cuts,
;; {:what :docstring :text "..." :finding-of (fn [shown-text] ...)}: the
;; finding `finding-of` (default `identity`) makes of a prefix of `text`,
;; `:what` (`:docstring` or `:source`) naming the text in the Truncation note.

(s/def ::what #{:docstring :source})

(s/def ::text string?)

(s/def ::finding-of fn?)

(s/def ::finding-cut (s/keys :req-un [::what ::text] :opt-un [::finding-of]))

(defn- single-finding-note
  "The Truncation note of a single finding cut to `shown` of its `total`
   characters of `what`; it has no paging hint.

  Keyword -> Long -> Long -> String"
  [what shown total]
  (str "truncated: " (name what) " shown " shown " of " total " chars"))

(defn cut-finding
  "The finding made of the longest prefix of the FindingCut's text that
   fits `room` characters together with its Truncation note on the next
   line.

  FindingCut -> Long -> String"
  [{:keys [what text finding-of] :or {finding-of identity}} room]
  (let [total (count text)
        cut (fn [shown]
              (str (finding-of (subs text 0 shown)) "\n"
                   (single-finding-note what shown total)))
        fits? (fn [shown] (<= (count (cut shown)) room))]
    (if (fits? 0)
      (loop [low 0
             high total]
        (if (< low high)
          (let [middle (quot (+ low high 1) 2)]
            (if (fits? middle)
              (recur middle high)
              (recur low (dec middle))))
          (cut low)))
      (text/ellipsis-cut room (cut 0)))))

;; ---------------------------------------------------------------------------
;; Shared answers
;; ---------------------------------------------------------------------------

;; AnswerContext: what an answer about Definitions is rendered with, built
;; once per call: {:budget OutputBudget :index CodeIndex
;; :signature-strategy SignatureStrategy}.

(s/def ::budget ::output-budget)

(s/def ::index :mcp.code-index/code-index)

(s/def ::signature-strategy ::docstring/signature-strategy)

(s/def ::answer-context (s/keys :req-un [::budget ::index ::signature-strategy]))

(defrecord AnswerContext [budget index signature-strategy])

(defn answer-context
  "The AnswerContext of a call with the tool's `args` on `index`.

  Config -> ToolArgs -> CodeIndex -> AnswerContext"
  [cfg args index]
  (->AnswerContext (budget cfg args) index (config/setting cfg :doc/signature)))

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

(defn definition-head
  "The start of a Definition's Compact answer line:
   `path:line  kind  ns/name  [arglists]`.

  CodeIndex -> Definition -> String"
  [index {:keys [filename row kind arglists] :as definition}]
  (->> [(str (relative-path (code-index/root index) filename) ":" row)
        kind
        (code-index/qualified-name definition)
        (st/join " " arglists)]
       (remove st/blank?)
       (st/join "  ")))

(defn definition->line
  "The Compact answer line of a Definition.

  The line is `path:line  kind  ns/name  [arglists]  Type signature
  Docstring summary`, the signature found by `signature-strategy`.
  `{:signature? false}` leaves the signature out of the line (it stays out
  of the summary).

  AnswerContext -> Definition -> String
  AnswerContext -> Definition -> {:signature? Boolean} -> String"
  ([ctx definition]
   (definition->line ctx definition {:signature? true}))
  ([{:keys [index signature-strategy]} definition {:keys [signature?]}]
   (let [{:keys [summary signature]} (docstring/definition-doc signature-strategy definition)]
     (->> [(definition-head index definition)
           (when signature? signature)
           summary]
          (remove st/blank?)
          (st/join "  ")))))

(defn ambiguous-header
  "The header line for `name-str` that denotes `candidate-count`
   candidates, naming what they are (`what`, such as \"definitions\").

  String -> String -> Long -> String"
  [what name-str candidate-count]
  (str "ambiguous: " candidate-count " " what " named " name-str ", pass a qualified name"))

(defn ambiguous
  "The answer for `name-str` that denotes several `definitions`: an
   `ambiguous-header`, then one line per candidate, with its Type
   signature, within the Output budget.

  AnswerContext -> String -> String -> [Definition] -> String"
  [{:keys [budget] :as ctx} what name-str definitions]
  (render-framed budget
                 {:header (ambiguous-header what name-str (count definitions))}
                 (mapv (partial definition->line ctx) definitions)))

(defn one-definition
  "The answer for `name-str` that resolved to `definitions`: `not-found`
   for none, `(answer-fn definition)` for exactly one, and for several
   `(ambiguous-fn definitions)`, by default the `ambiguous` candidates.

  AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> IO String
  AnswerContext -> String -> [Definition] -> (Definition -> IO String) -> ([Definition] -> String) -> IO String"
  ([ctx name-str definitions answer-fn]
   (one-definition ctx name-str definitions answer-fn
                   (partial ambiguous ctx "definitions" name-str)))
  ([{:keys [index]} name-str definitions answer-fn ambiguous-fn]
   (case (count definitions)
     0 (not-found index name-str)
     1 (answer-fn (first definitions))
     (ambiguous-fn definitions))))
