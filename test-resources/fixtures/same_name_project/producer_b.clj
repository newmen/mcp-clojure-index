(ns same-name-project.producer-b)

(defn produce
  [x]
  (assoc x :from :b))