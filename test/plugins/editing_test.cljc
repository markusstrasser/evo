(ns plugins.editing-test
  "Unit tests for editing plugin.

   Tests the :delete-forward intent according to TEXT_EDITING_TESTING_STRATEGY.md"
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]))

;; ── Delete Forward Tests ──────────────────────────────────────────────────────

(deftest delete-forward-middle-test
  (testing "Delete in middle of text removes next character"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 2  ; After "He"
                                                  :has-selection? false})
          db' (:db (tx/interpret db ops))]
      ;; Should delete "l"
      (is (= "Helo" (get-in db' [:nodes "a" :props :text])))
      ;; Cursor stays at position 2
      (is (= 2 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest delete-forward-at-end-test
  (testing "Delete at end with no children or siblings does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 5  ; At end
                                                  :has-selection? false})]
      ;; Should return empty ops (no-op)
      (is (empty? ops)))))

(deftest delete-forward-merge-with-sibling-test
  (testing "Delete at end merges with next sibling"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :create-node :id "b" :type :block :props {:text "Second"}}
                                 {:op :place :id "b" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 5  ; At end of "First"
                                                  :has-selection? false})
          db' (:db (tx/interpret db ops))]
      ;; Should merge with sibling
      (is (= "FirstSecond" (get-in db' [:nodes "a" :props :text])))
      ;; Block "b" should be deleted
      (is (= :trash (get-in db' [:derived :parent-of "b"])))
      ;; Cursor stays at original position (5)
      (is (= 5 (get-in db' [:nodes "session/ui" :props :cursor-position])))
      ;; Only one child under :doc now
      (is (= 1 (count (q/children db' :doc)))))))

(deftest delete-forward-merge-with-child-test
  (testing "Delete at end merges with first child (priority over sibling)"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :create-node :id "child" :type :block :props {:text "Child"}}
                                 {:op :place :id "child" :under "parent" :at :last}
                                 {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
                                 {:op :place :id "sibling" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "parent"
                                                  :cursor-pos 6  ; At end of "Parent"
                                                  :has-selection? false})
          db' (:db (tx/interpret db ops))]
      ;; Should merge with child, not sibling
      (is (= "ParentChild" (get-in db' [:nodes "parent" :props :text])))
      ;; Child should be deleted
      (is (= :trash (get-in db' [:derived :parent-of "child"])))
      ;; Sibling should still exist
      (is (= :doc (get-in db' [:derived :parent-of "sibling"])))
      ;; Cursor stays at position 6
      (is (= 6 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest delete-forward-merge-moves-grandchildren-test
  (testing "Delete at end moves target's children up when merging"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :create-node :id "child" :type :block :props {:text "Child"}}
                                 {:op :place :id "child" :under "parent" :at :last}
                                 {:op :create-node :id "grandchild" :type :block :props {:text "Grandchild"}}
                                 {:op :place :id "grandchild" :under "child" :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "parent"
                                                  :cursor-pos 6  ; At end
                                                  :has-selection? false})
          db' (:db (tx/interpret db ops))]
      ;; Parent text merged with child text
      (is (= "ParentChild" (get-in db' [:nodes "parent" :props :text])))
      ;; Grandchild should now be under parent
      (is (= "parent" (get-in db' [:derived :parent-of "grandchild"])))
      ;; Child should be deleted
      (is (= :trash (get-in db' [:derived :parent-of "child"]))))))

(deftest delete-forward-with-selection-test
  (testing "Delete with selection delegates to component"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 2
                                                  :has-selection? true})]
      ;; Should return empty ops (component handles)
      (is (empty? ops)))))
