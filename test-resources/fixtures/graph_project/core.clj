(ns graph-project.core
  (:require [graph-project.utils :as utils]))

(defn process
  [data]
  (-> data
      (utils/enrich data)
      (utils/validate data)))

(defn run
  [items]
  (mapv process items))