(ns ns-app.one
  (:require [ns-app.alpha :as store :refer [fetch]]))

(defn run
  "Runs on alpha."
  []
  [(fetch ::store/id) (store/save nil)])

(defrecord OneStore []
  store/Store
  (save [_] :one))

(defmethod store/handle :one [_] :one)
