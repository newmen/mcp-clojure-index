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

(def ^:private sentence-marks
  "The characters that end a sentence of a Docstring summary.

  #{Character}"
  #{\. \! \? \…})

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

(defn collapse-whitespace
  "`text` with every run of whitespace replaced by one space.

  String -> String"
  [text]
  (st/replace text #"\s+" " "))

(defn- collapsed-text
  "`text` with whitespace collapsed and trimmed; nil when nothing remains.

  Maybe String -> Maybe String"
  [text]
  (let [collapsed (-> (or text "") collapse-whitespace st/trim)]
    (when-not (st/blank? collapsed)
      collapsed)))

(defn- sentence-end
  "The index just past the first sentence of the whitespace-collapsed
   `text`; nil when the whole text is one sentence.

  A sentence ends at `.`, `!`, `?` or `…` outside backticks, followed by
  a space or by the end of the text.

  String -> Maybe Long"
  [^String text]
  (loop [index 0
         in-backticks? false]
    (when (< index (count text))
      (let [character (.charAt text index)
            next-index (inc index)]
        (cond
          (= \` character) (recur next-index (not in-backticks?))
          (and (not in-backticks?)
               (sentence-marks character)
               (or (= next-index (count text))
                   (= \space (.charAt text next-index)))) next-index
          :else (recur next-index in-backticks?))))))

(defn docstring-summary
  "The Docstring summary of `doc`: its first sentence with the source line
   breaks joined; nil when the docstring is blank.

  When text remains after the first sentence, the summary ends with
  `(+N chars)`, N being the remaining length with whitespace collapsed.

  Maybe String -> Maybe String"
  [doc]
  (when-let [text (collapsed-text doc)]
    (let [end (or (sentence-end text) (count text))
          remaining (st/trim (subs text end))]
      (cond-> (subs text 0 end)
        (not (st/blank? remaining)) (str " (+" (count remaining) " chars)")))))

;; SignatureStrategy: the value of `:doc/signature` in the config, the
;; built-in strategy that finds a Definition's Type signature:
;; {:strategy :last-paragraph} or {:strategy :pattern :regex "..."};
;; nil turns signatures off.

(s/def ::strategy keyword?)

(s/def ::regex string?)

(s/def ::signature-strategy (s/nilable (s/keys :req-un [::strategy] :opt-un [::regex])))

;; DefinitionDoc: what a Definition line shows of its docstring,
;; {:summary "First sentence. (+N chars)" :signature "String -> Long"};
;; either may be nil.

(s/def ::summary (s/nilable string?))

(s/def ::signature (s/nilable string?))

(s/def ::definition-doc (s/keys :req-un [::summary ::signature]))

;; SignatureSplit: what `split-signature` returns, the Type signature and
;; the text the Docstring summary is taken from,
;; {:text "First sentence. More." :signature "String -> Long"}; :signature
;; is nil when the docstring has none.

(s/def ::text string?)

(s/def ::signature-split (s/keys :req-un [::text ::signature]))

(defmulti split-signature
  "Splits the docstring `doc` into its Type signature and the text the
   Docstring summary is taken from, with the strategy of `signature-strategy`.

  A new strategy is a `defmethod` of this multimethod.

  SignatureStrategy -> String -> SignatureSplit"
  (fn [signature-strategy _doc] (:strategy signature-strategy)))

(defmethod split-signature :default
  [_signature-strategy doc]
  {:text doc :signature nil})

(defmethod split-signature :last-paragraph
  [_signature-strategy doc]
  (let [paragraphs (remove st/blank? (st/split doc #"\n\s*\n"))]
    (if (next paragraphs)
      {:text (st/join "\n\n" (butlast paragraphs))
       :signature (collapsed-text (last paragraphs))}
      {:text doc :signature nil})))

(defmethod split-signature :pattern
  [{:keys [regex]} doc]
  (let [^java.util.regex.Matcher matcher (re-matcher (re-pattern regex) doc)]
    (if (.find matcher)
      {:text (str (subs doc 0 (.start matcher)) " " (subs doc (.end matcher)))
       :signature (collapsed-text (.group matcher 1))}
      {:text doc :signature nil})))

(defn definition-doc
  "The Docstring summary and Type signature of `definition`, the
   signature found by `signature-strategy` and left out of the summary.

  Namespaces have no Type signature.

  SignatureStrategy -> Definition -> DefinitionDoc"
  [signature-strategy {:keys [kind doc]}]
  (if (or (st/blank? doc) (= "ns" kind))
    {:summary (docstring-summary doc) :signature nil}
    (let [{:keys [text signature]} (split-signature signature-strategy doc)]
      {:summary (docstring-summary text) :signature signature})))

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

(def ellipsis
  "The mark of a cut end of a text.

  String"
  "…")

(defn ellipsis-cut
  "The first `room` characters of `text`, its last one replaced by `…`.

  Long -> String -> String"
  [room text]
  (str (subs text 0 (min (max 0 (dec room)) (count text))) ellipsis))

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
         (oversized-first-finding (or cut #(ellipsis-cut %1 (text-of %2)))
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

;; FindingCut: what `cut-finding` cuts,
;; {:what :docstring :text "..." :finding-of (fn [shown-text] ...)}: the
;; finding `finding-of` (default `identity`) makes of a prefix of `text`,
;; `:what` (`:docstring` or `:source`) naming the text in the Truncation note.

(s/def ::what #{:docstring :source})

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
      (ellipsis-cut room (cut 0)))))

;; ---------------------------------------------------------------------------
;; Shared answers
;; ---------------------------------------------------------------------------

;; AnswerContext: what an answer about Definitions is rendered with, built
;; once per call: {:budget OutputBudget :index CodeIndex
;; :signature-strategy SignatureStrategy}.

(s/def ::budget ::output-budget)

(s/def ::index :mcp.code-index/code-index)

(s/def ::answer-context (s/keys :req-un [::budget ::index ::signature-strategy]))

(defrecord AnswerContext [budget index signature-strategy])

(defn answer-context
  "The AnswerContext of a call with the tool's `args` on `index`.

  Config -> ToolArgs -> CodeIndex -> AnswerContext"
  [cfg args index]
  (->AnswerContext (budget cfg args) index (:doc/signature cfg)))

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
   (let [{:keys [summary signature]} (definition-doc signature-strategy definition)]
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
