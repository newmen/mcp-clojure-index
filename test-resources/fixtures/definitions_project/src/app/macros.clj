(ns app.macros
  "Project macros.")

(defmacro defhandler
  "Defines a handler function, like defn."
  [handler-name & body]
  `(defn ~handler-name ~@body))
