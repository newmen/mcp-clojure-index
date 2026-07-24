(ns fixtures.valid-defs
  (:require [clojure.string :as s]))

(defn create-user
  "Creates a new user in the system."
  [name email]
  {:name (s/trim name)
   :email (s/trim email)})

(defn- format-name
  [name]
  (s/trim name))

(defmacro with-tx
  [& body]
  `(do ~@body))

(defprotocol UserProtocol
  (get-name [this])
  (set-name [this name]))

(defrecord User [name email]
  UserProtocol
  (get-name [_] (format-name name))
  (set-name [this new-name] (assoc this :name new-name)))

(deftype UserType [name email]
  UserProtocol
  (get-name [_] (format-name name))
  (set-name [_ new-name] (UserType. new-name email)))

(def MAX-RETRIES 3)

(def ^:dynamic *db-connection* nil)