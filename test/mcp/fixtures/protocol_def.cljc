(ns bank.protocols
  (:require [bank.user :as user]))

(defprotocol Transferable
  (transfer [this from to amount])
  (balance [this account]))

(defprotocol Comparable
  (compare-to [this other]))

(extend-type bank.user/User
  Transferable
  (transfer [this from to amount]
    (str "Transfer " amount " from " from " to " to))
  (balance [this account]
    1000))

(extend-protocol Transferable
  String
  (transfer [this from to amount]
    (str "String transfer " amount))
  (balance [this account]
    500))