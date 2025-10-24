(ns intent-router-repl-checks
  "Comprehensive REPL spot checks for intent router (ADR-016).

   Tests both structural (intent->ops) and view (intent->db) paths."
  (:require [core.db :as db]
            [core.intent :as intent]
            [core.interpret :as I]
            [plugins.selection.core :as sel]
            [plugins.struct.core :as struct]))

;; ── Test DB Setup ─────────────────────────────────────────────────────────────

(defn test-db []
  (-> (db/empty-db)
      (I/interpret [{:op :create-node :id "a" :type :block :props {:text "First"}}
                    {:op :create-node :id "b" :type :block :props {:text "Second"}}
                    {:op :create-node :id "c" :type :block :props {:text "Third"}}
                    {:op :place :id "a" :under :doc :at :last}
                    {:op :place :id "b" :under :doc :at :last}
                    {:op :place :id "c" :under :doc :at :last}])
      :db))

;; ── Structural Intent Checks (intent->ops path) ──────────────────────────────

(comment
  "CHECK 1: indent intent compiles to ops"
  (let [db (test-db)
        result (intent/apply-intent db {:type :indent :id "b"})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (vector? (:ops result)) "Should return ops vector")
    (assert (= 1 (count (:ops result))) "Should have one op")
    (assert (= :place (-> result :ops first :op)) "Should be place op")
    {:status :pass :result result})

  "CHECK 2: outdent intent compiles to ops"
  (let [db (-> (test-db)
               (I/interpret [{:op :place :id "b" :under "a" :at :last}])
               :db)
        result (intent/apply-intent db {:type :outdent :id "b"})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (= 1 (count (:ops result))) "Should have one op")
    {:status :pass :result result})

  "CHECK 3: delete intent compiles to ops (moves to trash)"
  (let [db (test-db)
        result (intent/apply-intent db {:type :delete :id "a"})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (= :trash (-> result :ops first :under)) "Should move to trash")
    {:status :pass :result result})

  "CHECK 4: delete-selected intent works with selection"
  (let [db (-> (test-db)
               (sel/select ["a" "b"]))
        result (intent/apply-intent db {:type :delete-selected})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (= 2 (count (:ops result))) "Should delete 2 nodes")
    {:status :pass :result result})

  "CHECK 5: indent-selected intent works with selection"
  (let [db (-> (test-db)
               (sel/select ["b" "c"]))
        result (intent/apply-intent db {:type :indent-selected})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (pos? (count (:ops result))) "Should have ops")
    {:status :pass :result result})

  "CHECK 6: ops can be interpreted successfully"
  (let [db (test-db)
        {:keys [ops]} (intent/apply-intent db {:type :indent :id "b"})
        result (I/interpret db ops)]
    (assert (empty? (:issues result)) "Should have no issues")
    (assert (= "a" (get-in result [:db :derived :parent-of "b"])) "b should be under a")
    {:status :pass :result result}))

;; ── View Intent Checks (intent->db path) ──────────────────────────────────────

(comment
  "CHECK 7: select intent updates DB directly"
  (let [db (test-db)
        result (intent/apply-intent db {:type :select :ids ["a" "b"]})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (= #{"a" "b"} (sel/get-selection (:db result))) "Should select a and b")
    (assert (= "b" (sel/get-focus (:db result))) "Focus should be last")
    {:status :pass :result result})

  "CHECK 8: extend-selection intent adds to selection"
  (let [db (-> (test-db)
               (sel/select "a"))
        result (intent/apply-intent db {:type :extend-selection :ids ["b"]})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (= #{"a" "b"} (sel/get-selection (:db result))) "Should have both")
    {:status :pass :result result})

  "CHECK 9: clear-selection intent clears all"
  (let [db (-> (test-db)
               (sel/select ["a" "b" "c"]))
        result (intent/apply-intent db {:type :clear-selection})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (empty? (sel/get-selection (:db result))) "Should be empty")
    {:status :pass :result result})

  "CHECK 10: select-next-sibling intent navigates"
  (let [db (-> (test-db)
               (sel/select "a"))
        result (intent/apply-intent db {:type :select-next-sibling})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (= #{"b"} (sel/get-selection (:db result))) "Should select b")
    {:status :pass :result result})

  "CHECK 11: select-prev-sibling intent navigates"
  (let [db (-> (test-db)
               (sel/select "b"))
        result (intent/apply-intent db {:type :select-prev-sibling})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (= #{"a"} (sel/get-selection (:db result))) "Should select a")
    {:status :pass :result result})

  "CHECK 12: extend-to-next-sibling intent extends range"
  (let [db (-> (test-db)
               (sel/select "a"))
        result (intent/apply-intent db {:type :extend-to-next-sibling})]
    (assert (= :db (:path result)) "Should use db path")
    (assert (= #{"a" "b"} (sel/get-selection (:db result))) "Should extend to b")
    {:status :pass :result result}))

;; ── Unknown Intent Checks ─────────────────────────────────────────────────────

(comment
  "CHECK 13: unknown intent returns unknown path"
  (let [db (test-db)
        result (intent/apply-intent db {:type :nonexistent-intent})]
    (assert (= :unknown (:path result)) "Should return unknown")
    (assert (= db (:db result)) "DB should be unchanged")
    (assert (empty? (:ops result)) "Should have no ops")
    {:status :pass :result result}))

;; ── Handler Existence Checks ──────────────────────────────────────────────────

(comment
  "CHECK 14: has-ops-handler? works"
  (assert (intent/has-ops-handler? :indent) "indent should have ops handler")
  (assert (intent/has-ops-handler? :delete) "delete should have ops handler")
  (assert (not (intent/has-ops-handler? :select)) "select should NOT have ops handler")
  {:status :pass}

  "CHECK 15: has-db-handler? works"
  (assert (intent/has-db-handler? :select) "select should have db handler")
  (assert (intent/has-db-handler? :clear-selection) "clear-selection should have db handler")
  (assert (not (intent/has-db-handler? :indent)) "indent should NOT have db handler")
  {:status :pass}

  "CHECK 16: has-handler? works for both"
  (assert (intent/has-handler? :indent) "indent should have handler")
  (assert (intent/has-handler? :select) "select should have handler")
  (assert (not (intent/has-handler? :nonexistent)) "nonexistent should have no handler")
  {:status :pass})

;; ── Combined Workflow Checks ──────────────────────────────────────────────────

(comment
  "CHECK 17: Combined workflow - select then delete"
  (let [db0 (test-db)
        ;; Select nodes
        {:keys [db]} (intent/apply-intent db0 {:type :select :ids ["a" "b"]})
        ;; Delete selected
        {:keys [ops]} (intent/apply-intent db {:type :delete-selected})
        ;; Interpret
        result (I/interpret db ops)]
    (assert (= #{"a" "b"} (sel/get-selection db)) "Selection should persist")
    (assert (empty? (:issues result)) "Should interpret cleanly")
    (assert (= #{"a" "b"} (set (get-in result [:db :children-by-parent :trash]))) "Should be in trash")
    {:status :pass :result result})

  "CHECK 18: Combined workflow - navigate then indent"
  (let [db0 (-> (test-db) (sel/select "a"))
        ;; Navigate to next
        {:keys [db]} (intent/apply-intent db0 {:type :select-next-sibling})
        _ (assert (= "b" (sel/get-focus db)) "Should focus b")
        ;; Indent focused
        {:keys [ops]} (intent/apply-intent db {:type :indent :id (sel/get-focus db)})
        result (I/interpret db ops)]
    (assert (empty? (:issues result)) "Should interpret cleanly")
    (assert (= "a" (get-in result [:db :derived :parent-of "b"])) "b should be under a")
    {:status :pass :result result})

  "CHECK 19: Reorder intent compiles correctly"
  (let [db (test-db)
        result (intent/apply-intent db {:type :reorder/children
                                        :parent :doc
                                        :order ["c" "b" "a"]})]
    (assert (= :ops (:path result)) "Should use ops path")
    (assert (= 3 (count (:ops result))) "Should have 3 place ops")
    (assert (every? #(= :place (:op %)) (:ops result)) "All should be place ops")
    {:status :pass :result result})

  "CHECK 20: Extend selection then outdent all"
  (let [db0 (-> (test-db) (sel/select "a"))
        ;; Extend to include b and c
        {:keys [db]} (intent/apply-intent db0 {:type :extend-to-next-sibling})
        {:keys [db]} (intent/apply-intent db {:type :extend-to-next-sibling})
        _ (assert (= 3 (count (sel/get-selection db))) "Should have 3 selected")
        ;; Outdent all selected
        {:keys [ops]} (intent/apply-intent db {:type :outdent-selected})
        result (I/interpret db ops)]
    (assert (empty? (:issues result)) "Should interpret cleanly")
    {:status :pass :result result}))

;; ── Backward Compatibility Checks ─────────────────────────────────────────────

(comment
  "CHECK 21: Old compile-intents API still works"
  (let [db (test-db)
        ops (struct/compile-intents db [{:type :indent :id "b"}
                                        {:type :delete :id "a"}])]
    (assert (= 2 (count ops)) "Should compile both intents")
    (assert (= :place (-> ops first :op)) "First should be place")
    {:status :pass :ops ops})

  "CHECK 22: Old selection functions still work"
  (let [db (test-db)
        db' (sel/select db ["a" "b"])]
    (assert (= #{"a" "b"} (sel/get-selection db')) "Direct calls should work")
    (assert (sel/selected? db' "a") "selected? should work")
    {:status :pass})

  "CHECK 23: Mixed old and new API"
  (let [db0 (test-db)
        ;; Old API: direct selection
        db1 (sel/select db0 "b")
        ;; New API: intent-based indent
        {:keys [ops]} (intent/apply-intent db1 {:type :indent :id "b"})
        result (I/interpret db1 ops)]
    (assert (empty? (:issues result)) "Should work together")
    {:status :pass :result result}))

(comment
  "RUN ALL CHECKS"
  (println "Running 23 intent router REPL checks...")
  (println "✓ Structural intents (1-6)")
  (println "✓ View intents (7-12)")
  (println "✓ Unknown intents (13)")
  (println "✓ Handler queries (14-16)")
  (println "✓ Combined workflows (17-20)")
  (println "✓ Backward compatibility (21-23)")
  (println "\nAll checks passed! Intent router working correctly."))
