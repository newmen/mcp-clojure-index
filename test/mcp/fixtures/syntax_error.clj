(ns fixtures.syntax-error)

(defn oops
  [x]
  (if x
    (println "yes")
  ;; missing closing paren