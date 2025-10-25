(ns core.selection-edit-boundary-test
  "Integration tests for intent boundary enforcement.

   Tests the core architectural invariant from ADR-017:
   - Selection intents may NOT modify :ui
   - Navigation intents may modify selection focus, NOT :ui
   - Editing intents may NOT modify selection state
   - Structural intents may NOT touch :ui or selection (except explicit focus after creation)

   These boundaries prevent UX issues like undo/redo restoring ephemeral state."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.api :as api]
            [kernel.db :as db]
            [kernel.query :as q]
            [kernel.transaction :as tx]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- setup-db-with-blocks
  "Create DB with test blocks a, b, c under :doc root."
  []
  (let [db0 (db/empty-db)
        ops [{:op :create-node :id "a" :type :block :props {:text "Block A"}}
             {:op :place :id "a" :under :doc :at :last}
             {:op :create-node :id "b" :type :block :props {:text "Block B"}}
             {:op :place :id "b" :under :doc :at :last}
             {:op :create-node :id "c" :type :block :props {:text "Block C"}}
             {:op :place :id "c" :under :doc :at :last}]
        result (tx/interpret db0 ops)]
    (when (seq (:issues result))
      (throw (ex-info "Setup failed" {:issues (:issues result)})))
    (:db result)))

;; =============================================================================
;; Boundary Tests
;; =============================================================================

(deftest selection-intents-dont-touch-ui
  (testing "Selection intents preserve :ui state (ephemeral boundary)"
    (let [db0 (-> (setup-db-with-blocks)
                  (assoc :ui {:editing-block-id "a"
                              :cursor {"a" {:first-row? true :last-row? false}}}))
          initial-ui (:ui db0)]

      (testing ":select intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :select :ids "b"})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":select must not modify :ui")))

      (testing ":toggle-selection intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :toggle-selection :ids "c"})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":toggle-selection must not modify :ui")))

      (testing ":extend-selection intent"
        (let [db-with-selection (-> (api/dispatch db0 {:type :select :ids "a"}) :db)
              initial-ui-2 (:ui db-with-selection)
              {db1 :db issues :issues} (api/dispatch db-with-selection {:type :extend-selection :ids "b"})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui-2 (:ui db1))
              ":extend-selection must not modify :ui")))

      (testing ":clear-selection intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :clear-selection})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":clear-selection must not modify :ui"))))))

(deftest editing-intents-dont-touch-selection
  (testing "Editing intents preserve selection state (structural boundary)"
    (let [db0 (-> (setup-db-with-blocks)
                  (api/dispatch {:type :select :ids "b"})
                  :db)
          initial-selection (q/selection db0)
          initial-focus (q/focus db0)]

      (testing ":enter-edit intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :enter-edit :block-id "b"})]
          (is (empty? issues) "No validation issues")
          (is (= initial-selection (q/selection db1))
              ":enter-edit must not modify selection")
          (is (= initial-focus (q/focus db1))
              ":enter-edit must not modify focus")
          (is (= "b" (q/editing-block-id db1))
              ":enter-edit should set editing-block-id")))

      (testing ":exit-edit intent"
        (let [db-editing (-> (api/dispatch db0 {:type :enter-edit :block-id "b"}) :db)
              {db1 :db issues :issues} (api/dispatch db-editing {:type :exit-edit})]
          (is (empty? issues) "No validation issues")
          (is (= initial-selection (q/selection db1))
              ":exit-edit must not modify selection")
          (is (= initial-focus (q/focus db1))
              ":exit-edit must not modify focus")
          (is (nil? (q/editing-block-id db1))
              ":exit-edit should clear editing-block-id")))

      (testing ":update-cursor-state intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :update-cursor-state
                                                           :block-id "b"
                                                           :first-row? true
                                                           :last-row? false})]
          (is (empty? issues) "No validation issues")
          (is (= initial-selection (q/selection db1))
              ":update-cursor-state must not modify selection")
          (is (= initial-focus (q/focus db1))
              ":update-cursor-state must not modify focus"))))))

(deftest navigation-intents-dont-touch-ui
  (testing "Navigation intents preserve :ui state (only modify selection focus)"
    (let [db0 (setup-db-with-blocks)
          initial-ui {:editing-block-id "a"
                      :cursor {"a" {:first-row? true :last-row? false}}}]

      (testing ":select-prev-sibling intent"
        (let [db-with-ui (-> (api/dispatch db0 {:type :select :ids "b"})
                             :db
                             (assoc :ui initial-ui))
              {db1 :db issues :issues} (api/dispatch db-with-ui {:type :select-prev-sibling})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":select-prev-sibling must not modify :ui")
          (is (= "a" (q/focus db1))
              ":select-prev-sibling should update focus to previous block")))

      (testing ":select-next-sibling intent"
        (let [db-with-ui (-> (api/dispatch db0 {:type :select :ids "a"})
                             :db
                             (assoc :ui initial-ui))
              {db1 :db issues :issues} (api/dispatch db-with-ui {:type :select-next-sibling})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":select-next-sibling must not modify :ui")
          (is (= "b" (q/focus db1))
              ":select-next-sibling should update focus to next block"))))))

(deftest structural-intents-respect-boundaries
  (testing "Structural intents may set focus after creation, but never touch :ui"
    (let [db0 (-> (setup-db-with-blocks)
                  (api/dispatch {:type :select :ids "a"})
                  :db
                  (assoc :ui {:editing-block-id "a"
                              :cursor {"a" {:first-row? true :last-row? false}}}))
          initial-ui (:ui db0)]

      (testing ":create-block may set focus, never :ui"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :create-block})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":create-block must not modify :ui")
          ;; Focus may change to newly created block (allowed)
          ))

      (testing ":delete-selected intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :delete-selected})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":delete-selected must not modify :ui")))

      (testing ":indent-selected intent"
        (let [{db1 :db issues :issues} (api/dispatch db0 {:type :indent-selected})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":indent-selected must not modify :ui")))

      (testing ":outdent-selected intent"
        (let [db-indented (-> (setup-db-with-blocks)
                              (api/dispatch {:type :select :ids "b"})
                              :db
                              (api/dispatch {:type :indent-selected})
                              :db
                              (assoc :ui initial-ui))
              {db1 :db issues :issues} (api/dispatch db-indented {:type :outdent-selected})]
          (is (empty? issues) "No validation issues")
          (is (= initial-ui (:ui db1))
              ":outdent-selected must not modify :ui"))))))
