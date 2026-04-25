(ns plugins.editing-test
  "Unit tests for editing plugin.

   Tests the :delete-forward intent according to TEXT_EDITING_TESTING_STRATEGY.md"
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [utils.session-patch :as session-patch]))

;; ── Session helpers ──────────────────────────────────────────────────────────

(defn empty-session
  "Create an empty session for testing."
  []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (session-patch/merge-patch session session-updates))

(defn run-intent
  "Run intent and return {:db ... :session ...}"
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session}))

;; ── Delete Forward Tests ──────────────────────────────────────────────────────

(deftest delete-forward-middle-test
  (testing "Delete in middle of text removes next character"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :delete-forward
                                                       :block-id "a"
                                                       :cursor-pos 2  ; After "He"
                                                       :has-selection? false})]
      ;; Should delete "l"
      (is (= "Helo" (get-in db [:nodes "a" :props :text])))
      ;; Cursor stays at position 2
      (is (= 2 (get-in session [:ui :cursor-position]))))))

(deftest delete-forward-at-end-test
  (testing "Delete at end with no children or siblings does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :delete-forward
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
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :delete-forward
                                                       :block-id "a"
                                                       :cursor-pos 5  ; At end of "First"
                                                       :has-selection? false})]
      ;; Should merge with sibling
      (is (= "FirstSecond" (get-in db [:nodes "a" :props :text])))
      ;; Block "b" should be deleted
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; Cursor stays at original position (5)
      (is (= 5 (get-in session [:ui :cursor-position])))
      ;; Only one child under :doc now
      (is (= 1 (count (q/children db :doc)))))))

(deftest delete-forward-merge-with-child-test
  (testing "Delete at end merges with first child (priority over sibling)"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :create-node :id "child" :type :block :props {:text "Child"}}
                                 {:op :place :id "child" :under "parent" :at :last}
                                 {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
                                 {:op :place :id "sibling" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :delete-forward
                                                       :block-id "parent"
                                                       :cursor-pos 6  ; At end of "Parent"
                                                       :has-selection? false})]
      ;; Should merge with child, not sibling
      (is (= "ParentChild" (get-in db [:nodes "parent" :props :text])))
      ;; Child should be deleted
      (is (= :trash (get-in db [:derived :parent-of "child"])))
      ;; Sibling should still exist
      (is (= :doc (get-in db [:derived :parent-of "sibling"])))
      ;; Cursor stays at position 6
      (is (= 6 (get-in session [:ui :cursor-position]))))))

(deftest delete-forward-merge-moves-grandchildren-test
  (testing "Delete at end moves target's children up when merging"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :create-node :id "child" :type :block :props {:text "Child"}}
                                 {:op :place :id "child" :under "parent" :at :last}
                                 {:op :create-node :id "grandchild" :type :block :props {:text "Grandchild"}}
                                 {:op :place :id "grandchild" :under "child" :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :delete-forward
                                               :block-id "parent"
                                               :cursor-pos 6  ; At end
                                               :has-selection? false})]
      ;; Parent text merged with child text
      (is (= "ParentChild" (get-in db [:nodes "parent" :props :text])))
      ;; Grandchild should now be under parent
      (is (= "parent" (get-in db [:derived :parent-of "grandchild"])))
      ;; Child should be deleted
      (is (= :trash (get-in db [:derived :parent-of "child"]))))))

(deftest delete-forward-with-selection-test
  (testing "Delete with selection delegates to component"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :delete-forward
                                                         :block-id "a"
                                                         :cursor-pos 2
                                                         :has-selection? true})]
      ;; Should return empty ops (component handles)
      (is (empty? ops)))))

;; ── Merge With Prev Tests (Backspace at start) ──────────────────────────────

(deftest merge-with-prev-basic-test
  (testing "Backspace at start merges with previous sibling"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :create-node :id "b" :type :block :props {:text "Second"}}
                                 {:op :place :id "b" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :merge-with-prev
                                                       :block-id "b"})]
      ;; Should merge with previous
      (is (= "FirstSecond" (get-in db [:nodes "a" :props :text])))
      ;; Block "b" should be deleted
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; Cursor should be at merge point
      (is (= 5 (get-in session [:ui :cursor-position])))
      ;; Should be editing previous block
      (is (= "a" (get-in session [:ui :editing-block-id]))))))

(deftest merge-with-prev-reparents-children-test
  (testing "CRITICAL: Backspace merge re-parents children to prev block"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Block A"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :create-node :id "b" :type :block :props {:text "Block B"}}
                                 {:op :place :id "b" :under :doc :at :last}
                                 ;; Children of block B
                                 {:op :create-node :id "child1" :type :block :props {:text "Child 1"}}
                                 {:op :place :id "child1" :under "b" :at :last}
                                 {:op :create-node :id "child2" :type :block :props {:text "Child 2"}}
                                 {:op :place :id "child2" :under "b" :at :last}]))
          session (empty-session)
          ;; Before merge: children under "b"
          _ (is (= ["child1" "child2"] (q/children db "b")))
          {:keys [db]} (run-intent db session {:type :merge-with-prev
                                               :block-id "b"})]
      ;; Text merged
      (is (= "Block ABlock B" (get-in db [:nodes "a" :props :text])))
      ;; Block B trashed
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; CRITICAL: Children should now be under "a" (not deleted!)
      (is (= "a" (get-in db [:derived :parent-of "child1"])))
      (is (= "a" (get-in db [:derived :parent-of "child2"])))
      ;; Children should be visible in "a"'s children list
      (is (= ["child1" "child2"] (q/children db "a"))))))

(deftest merge-with-prev-no-prev-test
  (testing "Backspace at start with no previous block does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Only block"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          result (intent/apply-intent db session {:type :merge-with-prev
                                                  :block-id "a"})]
      ;; Handler returns nil → apply-intent wraps as empty ops
      (is (empty? (:ops result))))))
