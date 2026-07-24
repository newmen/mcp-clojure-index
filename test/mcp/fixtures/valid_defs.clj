(ns fixtures.valid-defs
  (:require [clojure.string :as str]))

(defn create-user
  "Creates a new user in the system."
  [name email]
  (str/trim email)
  {:name name :email email})

(defn- format-name
  [name]
  (str/trim name))

(defmacro with-tx
  [& body]
  `(do ~@body))

(defprotocol UserProtocol
  (get-name [this])
  (set-name [this name]))

(defrecord User [name email]
  UserProtocol
  (get-name [this] name)
  (set-name [this new-name] (assoc this :name new-name)))

(deftype UserType [name email]
  UserProtocol
  (get-name [this] name))

(def MAX-RETRIES 3)

(def ^:dynamic *db-connection* nil)