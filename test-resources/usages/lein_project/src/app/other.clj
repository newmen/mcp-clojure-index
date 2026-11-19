(ns app.other
  (:require [app.core :as c :refer [get-uuid]]))

(defn caller
  [x]
  [(c/get-uuid x)
   (get-uuid x)
   (app.core/get-uuid x)
   (mapv #'get-uuid [x])
   (var c/get-uuid)
   (c/uses-value [x])])
