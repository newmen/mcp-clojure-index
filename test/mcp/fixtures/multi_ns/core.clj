(ns multi-ns.core
  (:require [multi-ns.utils :as utils]))

(defn process
  [data]
  (utils/enrich data)
  (utils/validate data))