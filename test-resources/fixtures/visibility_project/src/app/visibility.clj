(ns app.visibility
  "Private forms and specs, as `namespace_summary` writes them."
  (:require [clojure.spec.alpha :as s]))

(defn- hidden-fn [x] x)

(defmacro ^:private hidden-macro [x] x)

(def ^{:private true} hidden-map {})

(defprotocol ^:private Hidden
  (hide [this]))

(defrecord ^:private HiddenRecord [x])

(s/def ::own string?)
