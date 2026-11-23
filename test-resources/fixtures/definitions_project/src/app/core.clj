(ns app.core
  "Shapes, specs and handlers."
  (:require [clojure.spec.alpha :as s]
            [app.macros :refer [defhandler]]))

(defn helper
  "Shared helper."
  [x]
  (inc x))

(defmulti area
  "Area of a shape."
  :shape)

(defmethod area :circle
  [{:keys [r]}]
  (helper (* r r 3)))

(defmethod area [:sq :rect]
  [{:keys [w]}]
  (* w w))

(s/def ::uuid
  (s/and string? #(helper (count %))))

(defhandler handle-uuid
  "Handles a uuid."
  [req]
  (helper req))

(defrecord Circle [r]
  Object
  (toString [_] (str (helper r))))

(s/def :app.contract.Order/amount string?)

(s/def :plain-key int?)
