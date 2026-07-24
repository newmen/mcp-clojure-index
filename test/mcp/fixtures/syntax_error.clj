(ns broken.core)

(defn oops
  [x]
  (if x
    (println "yes")
  ;; missing closing paren