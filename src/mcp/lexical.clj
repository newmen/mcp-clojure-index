(ns mcp.lexical
  "Lexical matching of a query against Search units: BM25 over the words
   of the unit's name (`shop.orders/order-total` -> shop, order, order,
   total) and of its docstring. Pure; complements the vector
   search, which misses Definitions whose names and docstrings say
   exactly what the query asks for."
  (:require [clojure.string :as st]))

(set! *warn-on-reflection* true)

(def ^:private term-saturation
  "BM25 k1: how quickly repeated occurrences of a word stop adding weight.

  Double"
  1.2)

(def ^:private length-normalization
  "BM25 b: how much longer units are penalized.

  Double"
  0.75)

(def ^:private stop-words
  #{"a" "about" "after" "all" "an" "and" "any" "are" "as" "at" "be" "by"
    "each" "every" "for" "from" "how" "if" "in" "into" "is" "it" "its" "of" "on"
    "or" "so" "that" "the" "their" "this" "to" "when" "where" "which" "with"})

(def ^:private suffixes
  "Inflections stripped from a word, longest first.

  [String]"
  ["ing" "ed" "es" "s"])

(def ^:private min-stem-length 3)

;; ---------------------------------------------------------------------------
;; Words
;; ---------------------------------------------------------------------------

(defn- strip-suffix
  "`word` without its first matching inflection, unless the stem would be
   too short or the word ends with a double `s` (`address`).

  String -> String"
  [word]
  (or (some (fn [suffix]
              (when (and (st/ends-with? word suffix)
                         (>= (- (count word) (count suffix)) min-stem-length)
                         (not (st/ends-with? word "ss")))
                (subs word 0 (- (count word) (count suffix)))))
            suffixes)
      word))

(defn- stem
  "A crude stem, so that `orders`, `ordered` and `ordering` all give
   `order`, and `reduce`, `reduced` and `reducing` all give `reduc`.

  String -> String"
  [word]
  (let [stripped (strip-suffix word)]
    (cond-> stripped
      (and (st/ends-with? stripped "e") (> (count stripped) min-stem-length))
      (subs 0 (dec (count stripped))))))

(defn- words
  "The stemmed words of `text`, without stop words; camelCase is split.

  Maybe String -> [String]"
  [text]
  (->> (st/split (-> (str text)
                    (st/replace #"(\p{Ll}|\p{N})(\p{Lu})" "$1 $2")
                    st/lower-case)
                #"[^\p{L}\p{N}]+")
       (remove st/blank?)
       (remove stop-words)
       (map stem)))

(defn- unit-words
  "The stemmed words of a Search unit's name and docstring.

  SearchUnit -> [String]"
  [{unit-name :name docstring :doc}]
  (words (str unit-name " " docstring)))

;; ---------------------------------------------------------------------------
;; BM25
;; ---------------------------------------------------------------------------

(defn- inverse-document-frequency
  "The BM25 weight of a word that `containing-count` of `unit-count` units
   contain.

  Long -> Long -> Double"
  [unit-count containing-count]
  (Math/log (+ 1.0 (/ (+ (- unit-count containing-count) 0.5)
                      (+ containing-count 0.5)))))

(defn- scorer
  "A function of a unit's word frequencies to its BM25 score for
   `query-words`, given the word frequencies of every unit.

  [String] -> [{String Long}] -> ({String Long} -> Double)"
  [query-words all-frequencies]
  (let [unit-count (count all-frequencies)
        average-length (/ (reduce + (map (comp (partial reduce +) vals) all-frequencies))
                          (double (max 1 unit-count)))
        weights (into {}
                      (map (fn [word]
                             [word (inverse-document-frequency
                                    unit-count
                                    (count (filter #(contains? % word) all-frequencies)))]))
                      query-words)]
    (fn [frequencies]
      (let [length (reduce + (vals frequencies))
            normalization (* term-saturation
                             (+ (- 1 length-normalization)
                                (* length-normalization (/ length (max average-length 1.0)))))]
        (reduce + (for [word query-words
                        :let [frequency (get frequencies word 0)]
                        :when (pos? frequency)]
                    (* (weights word)
                       (/ (* frequency (inc term-saturation))
                          (+ frequency normalization)))))))))

;; ---------------------------------------------------------------------------
;; API
;; ---------------------------------------------------------------------------

(defn rank
  "The Search `units` sharing at least one word with `query`, best BM25
   match first; units with equal scores keep their order. Nil when `query`
   has no words.

  String -> [SearchUnit] -> Maybe [SearchUnit]"
  [query units]
  (let [query-words (distinct (words query))]
    (when (seq query-words)
      (let [all-frequencies (mapv (comp frequencies unit-words) units)
            score (scorer query-words all-frequencies)]
        (->> (map vector units (map score all-frequencies))
             (filter (comp pos? second))
             (sort-by (comp - second))
             (mapv first))))))
