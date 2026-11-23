(ns ns-app.two
  (:require [ns-app.beta :as store :refer [fetch]]))

(defn run
  "Runs on beta."
  []
  [(fetch ::store/id) (store/save nil)])

(defrecord TwoStore []
  store/Store
  (save [_] :two))

(defmethod store/handle :two [_] :two)
