(ns kw.other
  (:require [kw.core :as c]))

(defn caller
  []
  {::c/uuid "x"
   :timeout 10
   ::local 1})
