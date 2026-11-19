(ns kw.core
  (:require [clojure.spec.alpha :as s]))

(s/def ::uuid string?)

(defn read-opts
  [{:keys [timeout] :as opts}]
  [(::uuid opts)
   (:kw.core/uuid opts)
   (:timeout opts)
   timeout])
