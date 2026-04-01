(ns integration.navigation-scenarios-test
  "Integration-level scenarios wired to docs/LOGSEQ_BEHAVIOR_TRIADS.md."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [integration.fixtures :as fixtures]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [plugins.navigation])) ; ensure :navigate-to-adjacent is registered

(use-fixtures :once fixtures/bootstrap-runtime)

(def ^:private parent-text "Parent")

(defn- nav-boundary-left-db []
  (let [ops [{:op :create-node :id "parent" :type :block :props {:text parent-text}}
             {:op :place :id "parent" :under :doc :at :last}
             {:op :create-node :id "child" :type :block :props {:text "Kid"}}
             {:op :place :id "child" :under "parent" :at :last}]
        base (db/empty-db)]
    (:db (tx/interpret base ops))))

(deftest scenario-nav-boundary-left-01
  ;; Mirrors Scenario NAV-BOUNDARY-LEFT-01 in docs/LOGSEQ_BEHAVIOR_TRIADS.md
  (let [db (nav-boundary-left-db)
        session {:ui {:folded #{}} :selection {:nodes #{} :focus nil :anchor nil}}
        {:keys [session-updates]} (intent/apply-intent db session {:type :navigate-to-adjacent
                                                                    :direction :up
                                                                    :current-block-id "child"
                                                                    :cursor-position :max})]
    (testing "Parent becomes editing block at caret end"
      (is (= "parent" (get-in session-updates [:ui :editing-block-id])))
      (is (= (count parent-text) (get-in session-updates [:ui :cursor-position]))
          "Caret should land at end of parent text to mimic Logseq boundary jumps"))
    (testing "Cursor memory is not mutated (adjacent intent avoids column state)"
      (is (= nil (get-in session-updates [:ui :cursor-memory]))
          "Adjacent navigation should not write :cursor-memory, ensuring spec parity"))))
