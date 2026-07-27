(ns same-name-project.consumer
  (:require [same-name-project.producer-a :as a]
            [same-name-project.producer-b :as b]))

(defn run
  [x]
  (a/produce x))