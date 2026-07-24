(ns bank.ui
  (:require [reagent.core :as r]))

(defn greeting
  [name]
  [:div {:class "greeting"}
   [:h1 "Hello, " name]])

(defonce app-state (r/atom {:count 0}))