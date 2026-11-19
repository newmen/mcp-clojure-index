(ns app.core)

(defn get-uuid
  "Returns uuid."
  [x]
  x)

(defn uses-value
  [xs]
  (let [g get-uuid]
    [(partial get-uuid 1)
     (comp str get-uuid)
     (map get-uuid xs)
     (g 1)]))

(comment
  (get-uuid 99))
