(ns integration.navigation-scenarios-test
  "Integration-level scenarios wired to docs/specs/logseq_behaviors.md."
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.constants :as const]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [plugins.navigation])) ; ensure :navigate-to-adjacent is registered

(def ^:private parent-text "Parent")

(defn- nav-boundary-left-db []
  (let [ops [{:op :create-node :id "parent" :type :block :props {:text parent-text}}
             {:op :place :id "parent" :under :doc :at :last}
             {:op :create-node :id "child" :type :block :props {:text "Kid"}}
             {:op :place :id "child" :under "parent" :at :last}]
        base (db/empty-db)
        db* (:db (tx/interpret base ops))]
    (-> db*
        (assoc-in [:nodes const/session-ui-id :props :editing-block-id] "child")
        (assoc-in [:nodes const/session-ui-id :props :cursor-position] 0))))

(deftest scenario-nav-boundary-left-01
  ;; Mirrors Scenario NAV-BOUNDARY-LEFT-01 in docs/specs/logseq_behaviors.md
  (let [db (nav-boundary-left-db)
        {:keys [ops]} (intent/apply-intent db {:type :navigate-to-adjacent
                                               :direction :up
                                               :current-block-id "child"
                                               :cursor-position :max})
        exit-op (first ops)
        enter-op (last ops)]
    (testing "Exits current block before entering parent"
      (is (= nil (get-in exit-op [:props :editing-block-id]))))
    (testing "Parent becomes editing block at caret end"
      (is (= "parent" (get-in enter-op [:props :editing-block-id])))
      (is (= (count parent-text) (get-in enter-op [:props :cursor-position]))
          "Caret should land at end of parent text to mimic Logseq boundary jumps"))
    (testing "Cursor memory is not mutated (adjacent intent avoids column state)"
      (is (= nil (get-in exit-op [:props :cursor-memory]))
          "Adjacent navigation should not write :cursor-memory, ensuring spec parity"))))
