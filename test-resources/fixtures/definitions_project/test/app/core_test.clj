(ns app.core-test
  (:require [app.core :as core]
            [clojure.test :refer [deftest is]]))

(deftest helper-test
  (is (= 2 (core/helper 1))))
