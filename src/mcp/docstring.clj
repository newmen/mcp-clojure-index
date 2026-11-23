(ns mcp.docstring
  "The Docstring summary and the Type signature of a Definition's
   docstring (GLOSSARY.md), the signature found by the built-in strategy
   that `:doc/signature` of the config selects."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.text :as text]))

(set! *warn-on-reflection* true)

(def ^:private sentence-marks
  "The characters that end a sentence of a Docstring summary.

  #{Character}"
  #{\. \! \? \…})

;; ---------------------------------------------------------------------------
;; Docstring summary
;; ---------------------------------------------------------------------------

(defn- collapsed-text
  "`text` with whitespace collapsed and trimmed; nil when nothing remains.

  Maybe String -> Maybe String"
  [text]
  (let [collapsed (-> (or text "") text/collapse-whitespace st/trim)]
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

;; ---------------------------------------------------------------------------
;; Type signature
;; ---------------------------------------------------------------------------

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
