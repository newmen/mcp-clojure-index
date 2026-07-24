(ns multi-ns.utils)

(defn enrich
  [data]
  (assoc data :enriched true))

(defn validate
  [data]
  (assoc data :valid true))