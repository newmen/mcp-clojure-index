(ns mcp.text
  "Plain text helpers of Compact answers: collapsing whitespace and cutting
   a text to a number of characters with an ellipsis."
  (:require [clojure.string :as st]))

(set! *warn-on-reflection* true)

(def ellipsis
  "The mark of a cut end of a text.

  String"
  "…")

(defn collapse-whitespace
  "`text` with every run of whitespace replaced by one space.

  String -> String"
  [text]
  (st/replace text #"\s+" " "))

(defn ellipsis-cut
  "The first `room` characters of `text`, its last one replaced by `…`.

  Long -> String -> String"
  [room text]
  (str (subs text 0 (min (max 0 (dec room)) (count text))) ellipsis))

(defn around-column
  "The window of `line` around its 1-based `col`: the line with whitespace
   runs collapsed, cut to `width` characters that start at most `lead`
   characters before the column, an ellipsis marking each cut end.

  String -> Long -> Long -> Long -> String"
  [line col width lead]
  (let [column-index (min (count line) (max 0 (dec col)))
        before (collapse-whitespace (st/triml (subs line 0 column-index)))
        text (st/trimr (str before (collapse-whitespace (subs line column-index))))
        length (count text)
        start (max 0 (min (- (count before) lead) (- length width)))
        end (min length (+ start width))
        cut-start? (pos? start)
        cut-end? (< end length)]
    (str (when cut-start? ellipsis)
         (subs text (cond-> start cut-start? inc) (cond-> end cut-end? dec))
         (when cut-end? ellipsis))))
