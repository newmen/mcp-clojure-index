(ns ar.core)

(defn two
  ([x] x)
  ([x y] (+ x y)))

(defn many
  [x y & more]
  (apply + x y more))

(defn mixed
  ([x] x)
  ([x y z & more] (apply + x y z more)))

(defn callers
  []
  (two 1)
  (two 1 2 3)
  (many 1)
  (many 1 2 3 4)
  (mixed 3)
  (mixed 1 2)
  (map two [1 2])
  #'two)

(defn one
  [x]
  x)

(defn correct-caller
  []
  (one 1))
