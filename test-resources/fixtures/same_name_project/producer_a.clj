(ns same-name-project.producer-a)

(defn produce
  [x]
  (assoc x :from :a))