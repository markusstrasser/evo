(ns evolver.core-test
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [evolver.core :as core]))

(deftest app-state-initialization
  (testing "app-state atom is initialized correctly"
    (is (= {:counter 5} @core/app-state))))

(deftest app-state-updates
  (testing "app-state can be updated"
    (reset! core/app-state {:counter 10})
    (is (= {:counter 10} @core/app-state))
    (reset! core/app-state {:counter 5}))) ; Reset to original state

(deftest main-function-exists
  (testing "main function is defined"
    (is (fn? core/main))))

;; Uncomment the line below to run tests directly from this file in REPL
;; (run-tests)
