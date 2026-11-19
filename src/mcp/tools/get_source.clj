(ns mcp.tools.get-source
  "`get_source`: the source of exactly one Definition, so an agent never
   reads a whole file to see one function (ADR-0006)."
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [mcp.code-index :as code-index]
            [mcp.output :as output]
            [mcp.tool :as tool]
            [mcp.tools.find-definition :as find-definition]))

(set! *warn-on-reflection* true)

(def ^:private description
  (str "Get the source of exactly one Clojure Definition (function, macro, var, protocol, record, test or namespace form). "
       "Unlike reading the file or grep, returns only that form, resolved the way find_definition resolves names "
       "(simple, alias-qualified such as `c/get-uuid`, or fully qualified); an ambiguous name returns the candidates, not several bodies. "
       "Answer: a `path:start-end` header line, then the source; at most :output/max-chars characters, "
       "a longer body is cut with a note giving the line range not shown."))

(defmethod tool/spec :get_source
  [_tool-key]
  {:name "get_source"
   :description description
   :inputSchema {:type "object"
                 :properties {:name {:type "string"
                                     :description "Name: simple, alias-qualified or fully qualified"}}
                 :required ["name"]}})

;; ---------------------------------------------------------------------------
;; Source
;; ---------------------------------------------------------------------------

(defn- source-lines
  "Lines of the form spanning `row:col` to `end-row:end-col` (1-based,
   `end-col` exclusive) in `file-path`."
  [^String file-path {:keys [row col end-row end-col]}]
  (with-open [reader (io/reader file-path)]
    (let [lines (->> (line-seq reader) (drop (dec row)) (take (inc (- end-row row))) vec)
          last-index (dec (count lines))]
      (-> lines
          (update last-index #(subs % 0 (min (count %) (dec end-col))))
          (update 0 #(subs % (min (count %) (dec col))))))))

(defn- truncation-note
  [from-row to-row]
  (str "truncated: lines " from-row "-" to-row " not shown"))

(defn- source-answer
  "Header and source lines of `definition`, cut at a line boundary so the
   answer fits `:output/max-chars`."
  [config root {:keys [filename row end-row] :as definition}]
  (let [header (str (output/relative-path root filename) ":" row "-" end-row)
        lines (source-lines filename definition)
        answers (->> (range (count lines) -1 -1)
                     (map (fn [shown]
                            (s/join "\n" (cond-> (into [header] (take shown lines))
                                           (< shown (count lines))
                                           (conj (truncation-note (+ row shown) end-row)))))))]
    (or (first (filter #(<= (count %) (:output/max-chars config)) answers))
        (last answers))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(defmethod tool/call :get_source
  [_tool-key params {:keys [config code-index]}]
  (let [name-str (s/trim (str (:name params)))
        root (:root code-index)
        definitions (code-index/resolve-name code-index name-str)]
    (tool/text-result
     (case (count definitions)
       0 (find-definition/not-found code-index name-str)
       1 (source-answer config root (first definitions))
       (find-definition/ambiguous config {} name-str
                                  (mapv (partial find-definition/definition->line root) definitions))))))
