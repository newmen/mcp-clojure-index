(ns ur.qualified-refer
  (:require [ur.lib :refer [a b]]))

(defn f [] (a) (ur.lib/b))
