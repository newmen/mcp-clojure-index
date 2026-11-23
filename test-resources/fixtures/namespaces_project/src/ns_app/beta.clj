(ns ns-app.beta
  "Beta store."
  (:require [clojure.spec.alpha :as s]))

(s/def ::id int?)

(defn fetch
  "Fetches from beta."
  [id]
  id)

(defprotocol Store
  (save [this]))

(defmulti handle :type)
