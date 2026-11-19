(ns app.core-spec
  (:require [app.core :as core] [clojure.test :refer [deftest]]))

(defn check-uuid
  []
  (core/get-uuid 1))

(deftest get-uuid-test
  (with-redefs [core/get-uuid (fn [x] [:stub x])]
    (check-uuid)))
