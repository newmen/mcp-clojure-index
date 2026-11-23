(ns ur.qualified
  (:require [ur.lib :as lib]))

(defn f [] (ur.lib/a))

(def g #'lib/b)
