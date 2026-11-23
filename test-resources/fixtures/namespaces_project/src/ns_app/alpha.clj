(ns ns-app.alpha
  "Alpha store."
  (:require [clojure.spec.alpha :as s]))

(s/def ::id string?)

(defn fetch
  "Fetches from alpha."
  [id]
  id)

(defprotocol Store
  (save [this]))

(defmulti handle :type)
