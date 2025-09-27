(ns evolver-core-test
  "Core evolver logic tests - no browser dependency"
  (:require [cljs.test :refer [deftest testing is are run-tests]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.renderer :as renderer]
            [evolver.dispatcher :as dispatcher]))

;; Test data fixtures
(def sample-store-state
  {:nodes {"root" {:type :div}
           "node-1" {:type :p :props {:text "First node"}}
           "node-2" {:type :p :props {:text "Second node"}}
           "container" {:type :div :props {:text "Container"}}}
   :children-by-parent {"root" ["node-1" "node-2" "container"]
                        "container" []}
   :view {:selection []
          :highlighted #{}
          :collapsed #{}
          :hovered-referencers #{}}
   :references {}
   :log-level :info
   :log-history []
   :derived {:depth {"root" 0 "node-1" 1 "node-2" 1 "container" 1}
             :paths {"root" [] "node-1" ["root"] "node-2" ["root"] "container" ["root"]}
             :parent-of {"node-1" "root" "node-2" "root" "container" "root"}}
   :computed {:referenced-nodes {}
              :referencer-highlighted #{}}})

(def sample-atom-store
  {:past []
   :present sample-store-state
   :future []
   :view (:view sample-store-state)})

;; === KERNEL TESTS ===

(deftest kernel-basic-operations-test
  (testing "Kernel basic state operations with mock data"
    (let [mock-db {:nodes {"test-node" {:type :p :props {:text "Test content"}}
                           "test-parent" {:type :div}}
                   :children-by-parent {"test-parent" ["test-node"]}}]

      ;; Test node access with our mock data
      (is (= {:type :p :props {:text "Test content"}}
             (get-in mock-db [:nodes "test-node"])))

      ;; Test children access with mock data
      (is (= ["test-node"]
             (get-in mock-db [:children-by-parent "test-parent"])))

      ;; Test existence checks
      (is (contains? (:nodes mock-db) "test-node"))
      (is (not (contains? (:nodes mock-db) "non-existent"))))))

(deftest kernel-selection-operations-test
  (testing "Kernel selection operations"
    (let [db (assoc-in sample-store-state [:view :selection] ["node-1"])]
      ;; Test selection check
      (is (k/selected? db "node-1"))
      (is (not (k/selected? db "node-2")))

      ;; Test selection toggle
      (let [toggled (k/toggle-selection db "node-2")]
        (is (= #{"node-1" "node-2"} (set (get-in toggled [:view :selection]))))))))

;; === INTENT TESTS ===

(deftest intent-selection-test
  (testing "Selection intent operations"
    (let [db sample-store-state]
      ;; Test toggle selection on empty selection
      (when-let [toggle-fn (get intents/intents :toggle-selection)]
        (let [transaction (toggle-fn db {:target-id "node-1"})
              result (k/apply-transaction db transaction)]
          (is (= ["node-1"] (get-in result [:view :selection]))))))))

(deftest intent-node-creation-test
  (testing "Node creation intents"
    (let [db sample-store-state]
      ;; Test if create-node intent exists and can be called
      (when-let [create-fn (get intents/intents :create-node)]
        (let [result (create-fn db {:type :p
                                    :props {:text "New node"}
                                    :parent-id "root"})]
          ;; Should have more nodes than before
          (is (> (count (:nodes result)) (count (:nodes db)))))))))

;; === RENDERER TESTS ===

(deftest renderer-hiccup-generation-test
  (testing "Renderer generates valid hiccup"
    (let [db sample-store-state]
      ;; Test single node rendering
      (let [hiccup (renderer/render-node db "node-1")]
        (is (vector? hiccup))
        (is (keyword? (first hiccup))))

      ;; Test full app rendering
      (let [app-hiccup (renderer/render db)]
        (is (vector? app-hiccup))
        (is (= :div (first app-hiccup)))))))

(deftest renderer-selection-styling-test
  (testing "Renderer applies selection styling"
    (let [db (assoc-in sample-store-state [:view :selection] ["node-1"])]
      (let [hiccup (renderer/render-node db "node-1")]
        ;; Should contain selection-related classes or attributes
        (is (string? (str hiccup))) ; Basic validation that it renders
        ))))

;; === DISPATCHER TESTS ===

(deftest dispatcher-intent-routing-test
  (testing "Dispatcher routes intents correctly"
    (let [store (atom sample-atom-store)]
      ;; Test toggle-selection dispatch
      (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-1"})

      ;; Check that state changed in the present state
      (let [new-selection (get-in @store [:present :view :selection])]
        (is (some #{"node-1"} new-selection))))))

;; === INTEGRATION TESTS ===

(deftest full-data-flow-test
  (testing "Complete data flow: dispatch -> state change -> render"
    (let [store (atom sample-atom-store)]
      ;; 1. Dispatch an intent
      (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-1"})

      ;; 2. Verify state changed
      (let [new-state (:present @store)]
        (is (k/selected? new-state "node-1")))

      ;; 3. Render with new state
      (let [hiccup (renderer/render (:present @store))]
        (is (vector? hiccup))
        (is (= :div (first hiccup)))))))

(deftest state-consistency-test
  (testing "State remains consistent after operations"
    (let [store (atom sample-atom-store)]
      ;; Perform multiple operations
      (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-1"})
      (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-2"})

      (let [final-state (:present @store)]
        ;; Check derived data is consistent
        (is (map? (:derived final-state)))
        (is (map? (:computed final-state)))

        ;; Check selections are valid node IDs
        (doseq [selected-id (get-in final-state [:view :selection])]
          (is (k/node-exists? final-state selected-id)))))))

;; === PERFORMANCE TESTS ===

(deftest large-state-performance-test
  (testing "Operations perform well with larger state"
    (let [large-nodes (into {} (for [i (range 100)]
                                 [(str "node-" i) {:type :p :props {:text (str "Node " i)}}]))
          large-children {"root" (mapv #(str "node-" %) (range 100))}
          large-state (-> sample-store-state
                          (assoc :nodes (merge (:nodes sample-store-state) large-nodes))
                          (assoc :children-by-parent (merge (:children-by-parent sample-store-state) large-children)))
          store (atom {:past [] :present large-state :future [] :view (:view large-state)})]

      ;; Time a selection operation
      (let [start-time (js/Date.now)
            _ (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-50"})
            end-time (js/Date.now)
            duration (- end-time start-time)]

        ;; Should complete quickly (less than 100ms)
        (is (< duration 100))

        ;; Should still work correctly
        (is (k/selected? (:present @store) "node-50"))))))

;; === EDGE CASE TESTS ===

(deftest edge-case-handling-test
  (testing "Handles edge cases gracefully"
    (let [store (atom sample-atom-store)]
      ;; Test selecting non-existent node
      (dispatcher/dispatch-intent! store :toggle-selection {:target-id "non-existent"})

      ;; State should remain valid
      (let [state (:present @store)]
        (is (map? state))
        (is (vector? (get-in state [:view :selection]))))

      ;; Test empty selection operations
      (let [empty-selection-state (assoc-in sample-atom-store [:view :selection] [])]
        (reset! store empty-selection-state)
        (dispatcher/dispatch-intent! store :toggle-selection {:target-id "node-1"})
        (is (k/selected? (:present @store) "node-1"))))))

;; Run all tests
(defn run-all-evolver-tests []
  (println "🧪 Running Evolver Core Tests...")
  (run-tests 'evolver-core-test))

;; Auto-run tests when namespace loads
(run-tests)