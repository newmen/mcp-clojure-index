(ns mcp.output-test
  (:require [clojure.string :as st]
            [clojure.test :refer [deftest is testing]]
            [mcp.output :as sut]))

(set! *warn-on-reflection* true)

(def ^:private budget-cfg
  {:output/limit 3 :output/max-chars 2000})

(def ^:private findings
  ["a.clj:1  defn  a/one"
   "a.clj:2  defn  a/two"
   "a.clj:3  defn  a/three"
   "a.clj:4  defn  a/four"
   "a.clj:5  defn  a/five"])

(deftest render-clamps-limit-above-ceiling
  (is (= (str "a.clj:1  defn  a/one\n"
              "a.clj:2  defn  a/two\n"
              "a.clj:3  defn  a/three\n"
              "truncated: shown 3 of 5, pass offset=3 or narrow the query")
         (sut/render (sut/budget budget-cfg {:limit 1000}) findings))))

(deftest render-offset-returns-next-page-without-repeating
  (is (= (str "a.clj:3  defn  a/three\n"
              "a.clj:4  defn  a/four\n"
              "truncated: shown 4 of 5, pass offset=4 or narrow the query")
         (sut/render (sut/budget budget-cfg {:limit 2 :offset 2}) findings)))
  (is (= "a.clj:5  defn  a/five"
         (sut/render (sut/budget budget-cfg {:limit 2 :offset 4}) findings))))

(deftest render-cuts-at-line-boundary-when-max-chars-reached
  (let [answer (sut/render (sut/budget {:output/limit 50 :output/max-chars 100} {}) findings)]
    (is (= (str "a.clj:1  defn  a/one\n"
                "a.clj:2  defn  a/two\n"
                "truncated: shown 2 of 5, pass offset=2 or narrow the query")
           answer))
    (is (<= (count answer) 100))))

(deftest render-cuts-a-single-oversized-finding-to-fit
  (let [huge (apply str (repeat 500 "x"))
        answer (sut/render (sut/budget {:output/limit 50 :output/max-chars 100} {}) [huge "b.clj:1"])]
    (is (<= (count answer) 100))
    (is (st/ends-with? answer "truncated: shown 1 of 2, pass offset=1 or narrow the query"))))

(deftest render-without-truncation-has-no-note
  (is (= "a.clj:1  defn  a/one\na.clj:2  defn  a/two"
         (sut/render (sut/budget budget-cfg {}) (take 2 findings)))))

(deftest relative-path-strips-project-root
  (is (= "src/mcp/tools.clj" (sut/relative-path "/home/p/proj" "/home/p/proj/src/mcp/tools.clj")))
  (is (= "src/a.clj" (sut/relative-path "/home/p/proj/" "/home/p/proj/src/a.clj")))
  (is (= "src/a.clj" (sut/relative-path "/home/p/proj" "src/a.clj")))
  (is (= "/elsewhere/a.clj" (sut/relative-path "/home/p/proj" "/elsewhere/a.clj"))))

(deftest docstring-summary-joins-wrapped-lines-of-the-first-sentence
  (is (= "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. (+32 chars)"
         (sut/docstring-summary
          "Возвращает путь к файлу относительно корня
   проекта, если файл лежит внутри него.

  Maybe String -> String -> String"))))

(deftest docstring-summary-counts-remaining-chars-only-when-text-remains
  (is (= "One sentence." (sut/docstring-summary "One sentence.")))
  (is (= "One sentence." (sut/docstring-summary "  One\n  sentence.  \n")))
  (is (= "First. (+7 chars)" (sut/docstring-summary "First.\n\n  Second.")))
  (is (= "No full stop at all" (sut/docstring-summary "No full stop at all")))
  (is (nil? (sut/docstring-summary nil)))
  (is (nil? (sut/docstring-summary "  \n "))))

(deftest docstring-summary-ends-at-sentence-marks-outside-backticks
  (testing "`e.g.` cuts the sentence, as accepted by the spec"
    (is (= "Caches a value, e.g. (+19 chars)"
           (sut/docstring-summary "Caches a value, e.g. `foo!` in the atom."))))
  (testing "a backticked `foo!` does not cut"
    (is (= "Calls `set-cached-tc!` before reading. (+5 chars)"
           (sut/docstring-summary "Calls `set-cached-tc!` before reading.\n\n  IO ()"))))
  (testing "`!`, `?` and `…` end a sentence too"
    (is (= "Stop! (+4 chars)" (sut/docstring-summary "Stop! Now.")))
    (is (= "Why? (+4 chars)" (sut/docstring-summary "Why? Now.")))
    (is (= "Wait… (+4 chars)" (sut/docstring-summary "Wait… Now."))))
  (testing "a mark not followed by whitespace does not cut"
    (is (= "Uses mcp.output/budget.edn for the rules"
           (sut/docstring-summary "Uses mcp.output/budget.edn for the rules")))))

(def ^:private last-paragraph
  "The default signature strategy.

  SignatureStrategy"
  {:strategy :last-paragraph})

(deftest last-paragraph-strategy-takes-the-signature-out-of-the-summary
  (testing "a Russian docstring wrapped over several lines, signature as its last paragraph"
    (is (= {:summary "Возвращает путь к файлу относительно корня проекта, если файл лежит внутри него. (+38 chars)"
            :signature "Maybe String -> String -> String"}
           (sut/definition-doc last-paragraph
             {:kind "defn"
              :doc "Возвращает путь к файлу относительно корня
   проекта, если файл лежит внутри него. Иначе путь
   возвращается без изменений.

  Maybe String -> String -> String"}))))
  (testing "a signature over several lines is joined with single spaces"
    (is (= {:summary "Checks a value."
            :signature "Config -> IO String Config -> Long -> IO String"}
           (sut/definition-doc last-paragraph
             {:kind "defn"
              :doc "Checks a value.\n\n  Config -> IO String\n  Config -> Long -> IO String"}))))
  (testing "a `def` whose signature is a bare type"
    (is (= {:summary "Rows per page." :signature "Long"}
           (sut/definition-doc last-paragraph {:kind "def" :doc "Rows per page.\n\n  Long"}))))
  (testing "a one-paragraph docstring has no signature, its paragraph is the summary"
    (is (= {:summary "Lines per page. (+4 chars)" :signature nil}
           (sut/definition-doc last-paragraph {:kind "def" :doc "Lines per page.\n  Ten."}))))
  (testing "namespaces have no signature"
    (is (= {:summary "Output budget. (+12 chars)" :signature nil}
           (sut/definition-doc last-paragraph {:kind "ns" :doc "Output budget.\n\n  Second part."}))))
  (testing "no docstring"
    (is (= {:summary nil :signature nil}
           (sut/definition-doc last-paragraph {:kind "defn" :doc nil})))))

(deftest pattern-strategy-takes-the-first-group-of-the-regex
  (let [strategy {:strategy :pattern :regex "Type:\\s+(.+?)\\s*$"}]
    (testing "the matched text is not part of the summary"
      (is (= {:summary "Signs a request. (+13 chars)" :signature "String -> IO String"}
             (sut/definition-doc strategy {:kind "defn"
                                           :doc "Signs a request. With the key.\n  Type: String -> IO String"}))))
    (testing "no match, no signature"
      (is (= {:summary "Signs a request." :signature nil}
             (sut/definition-doc strategy {:kind "defn" :doc "Signs a request."}))))))

(deftest without-a-strategy-there-are-no-signatures
  (is (= {:summary "Rows per page. (+4 chars)" :signature nil}
         (sut/definition-doc nil {:kind "def" :doc "Rows per page.\n\n  Long"}))))
