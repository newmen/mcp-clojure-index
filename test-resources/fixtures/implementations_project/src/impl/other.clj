(ns impl.other
  (:require [impl.shapes :as sh]))

(defrecord Triangle [a b c])

(extend-type Triangle
  sh/Shape
  (area [_] 0))

(defmethod sh/render :triangle [_] "^")
