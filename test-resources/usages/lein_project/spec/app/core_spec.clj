(ns app.core-spec
  (:require [app.core :as core]))

(defn check-uuid
  []
  (core/get-uuid 1))
