;; Licensed under the Apache License, Version 2.0.
;; This header never becomes a Description.
(ns app.comments
  (:require [clojure.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Section header, separated from the form below by a blank line.

(def batch-size 100)

;; An order identifier: a non-blank
;; string. Assigned by the shop.
;;
;; String
(s/def ::order-id
  (s/and string? #(pos? (count %))))

  ;;; Retries before giving up.
(def max-retries 3)

;; This comment loses to the docstring.
(def timeout-ms
  "Milliseconds to wait for a reply."
  5000)
