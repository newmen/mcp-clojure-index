(ns mcp.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp.text :as sut]))

(set! *warn-on-reflection* true)

(deftest ellipsis-cut-keeps-room-characters-with-the-ellipsis
  (is (= "abc…" (sut/ellipsis-cut 4 "abcdefgh")))
  (is (= "…" (sut/ellipsis-cut 0 "abc"))))

(deftest around-column-collapses-whitespace-and-cuts-both-ends
  (testing "a short line is shown whole, whitespace collapsed and trimmed"
    (is (= "(f x y)" (sut/around-column "    (f   x\ty)  " 5 80 20))))
  (testing "a long line is cut around the column, at most `lead` characters before it"
    (is (= "…6789(f x)0123…"
           (sut/around-column "0123456789(f x)0123456789" 11 15 5))))
  (testing "near the start only the end is cut"
    (is (= "(f x)0123…" (sut/around-column "(f x)0123456789" 1 10 5)))))
