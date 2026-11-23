(ns mcp.in-namespace
  "Names as written in a namespace: the `ns` argument of the precise tools
   (`find_definition`, `find_usages`, `find_implementations`,
   `get_source`), which resolve a name as that namespace's source would."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as st]
            [mcp.code-index :as code-index]))

(set! *warn-on-reflection* true)

;; NameLookup: what a tool's lookup of a name gave when the agent named
;; the namespace it is written in, {:result a :unresolvable "..."}.
;; `:unresolvable` (the line `not resolvable in <ns>, all candidates:`) is
;; there only when the name does not resolve in that namespace; `:result`
;; is then the lookup of the name as it is, without the namespace.

(s/def ::result any?)

(s/def ::unresolvable string?)

(s/def ::name-lookup (s/keys :req-un [::result] :opt-un [::unresolvable]))

(defn- namespace-arg
  "The `ns` tool argument of `args`, trimmed; nil when it is absent or
   blank.

  ToolArgs -> Maybe String"
  [args]
  (some-> (:ns args) str st/trim not-empty))

(defn answer
  "The answer `answer-of` gives for the `ns` argument of `args` (nil when
   there is none), or `unknown namespace: <ns>` alone when that argument
   names no namespace of the project.

  CodeIndex -> ToolArgs -> (Maybe String -> IO String) -> IO String"
  [index args answer-of]
  (let [ns-str (namespace-arg args)]
    (if (and ns-str (not (code-index/namespace-name? index ns-str)))
      (str "unknown namespace: " ns-str)
      (answer-of ns-str))))

(defn lookup
  "Looks `name-str` up with `lookup-of` as written in the namespace
   `ns-str` (as it is when `ns-str` is nil). A name the namespace leaves as
   it is (a qualified name or keyword, a namespace name) is looked up as
   it is; one that does not resolve there, or whose lookup there is not
   `found?`, is looked up as it is and marked unresolvable.

  CodeIndex -> Maybe String -> (String -> a) -> (a -> Boolean) -> String -> NameLookup"
  [index ns-str lookup-of found? name-str]
  (let [qualified (when ns-str (code-index/qualify-in-namespace index ns-str name-str))
        in-namespace (some-> qualified lookup-of)]
    (cond (nil? ns-str) {:result (lookup-of name-str)}
          (or (= (st/trim name-str) qualified)
              (and (some? in-namespace) (found? in-namespace))) {:result in-namespace}
          :else {:result (lookup-of name-str)
                 :unresolvable (str "not resolvable in " ns-str ", all candidates:")})))

(defn definitions
  "The NameLookup of the Definitions `name-str` denotes as written in the
   namespace `ns-str` (as it is when `ns-str` is nil).

  CodeIndex -> Maybe String -> String -> NameLookup"
  [index ns-str name-str]
  (lookup index ns-str (partial code-index/resolve-name index) seq name-str))

(defn headed
  "`group` with the `not resolvable` line of the NameLookup above its
   header, when the name did not resolve in the namespace.

  NameLookup -> AnswerGroup -> AnswerGroup"
  [{:keys [unresolvable]} group]
  (cond-> group
    unresolvable (update :header (partial str unresolvable "\n"))))
