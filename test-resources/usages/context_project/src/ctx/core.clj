(ns ctx.core)

(defn target
  [x & more]
  (into [x] more))

(defn caller
  [x]
  (target    x
             (:mode x)))

(defn long-caller
  [x]
  (let [first-very-long-binding-name (str "padding padding padding padding" x) result (target first-very-long-binding-name :a-rather-long-keyword-argument :another-long-keyword-argument :and-one-more)] result))
