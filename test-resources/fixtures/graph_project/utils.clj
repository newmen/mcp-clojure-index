(ns graph-project.utils)

(defn enrich
  [data _]
  (assoc data :enriched true))

(defn validate
  [data _]
  (assoc data :valid true))

(defn transform
  [data]
  (-> data
      (assoc :transformed true)
      (enrich data)))