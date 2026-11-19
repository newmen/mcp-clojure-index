(ns impl.shapes
  "Protocol and multimethod with Implementations of every form.")

(defprotocol Shape
  (area [this])
  (perimeter [this]))

(defrecord Circle [r]
  Shape
  (area [_] (* 3 r r))
  (perimeter [_] (* 2 3 r)))

(deftype Square [a]
  Shape
  (area [_] (* a a)))

(extend-type String
  Shape
  (area [s] (count s)))

(extend-protocol Shape
  nil
  (area [_] 0)
  Long
  (area [n] n)
  (perimeter [n] n))

(defn make-shape
  []
  (reify Shape
    (area [_] 1)))

(defmulti render :kind)

(defmethod render :circle [_] "o")

(defmethod render [:sq :rect] [_] "[]")
