(ns pure-logic-test
  "Pure logic tests with complete mock data - no external dependencies"
  (:require [cljs.test :refer [deftest testing is run-tests]]))

;; === MOCK DATA FIXTURES ===

(def mock-node-fixture
  {:id "test-node-1"
   :type :p
   :props {:text "Mock paragraph content"}})

(def mock-store-fixture
  {:nodes {"root" {:type :div}
           "para-1" {:type :p :props {:text "First paragraph"}}
           "para-2" {:type :p :props {:text "Second paragraph"}}
           "div-1" {:type :div :props {:text "Container div"}}}
   :children-by-parent {"root" ["para-1" "para-2" "div-1"]
                        "div-1" []}
   :view {:selection []
          :highlighted #{}
          :collapsed #{}}})

(def mock-hiccup-fixtures
  {:simple [:p "Simple text"]
   :with-attrs [:div {:class "container"} "Text with attributes"]
   :nested [:div {:class "parent"}
            [:p "Child paragraph"]
            [:span "Child span"]]})

;; === PURE DATA TRANSFORMATION TESTS ===

(deftest selection-toggle-logic-test
  (testing "Selection toggle logic - pure function"
    (let [empty-selection []
          with-item ["item-1"]
          with-multiple ["item-1" "item-2"]]

      ;; Adding to empty selection
      (let [add-to-empty (conj empty-selection "new-item")]
        (is (= ["new-item"] add-to-empty)))

      ;; Adding to existing selection
      (let [add-to-existing (conj with-item "item-2")]
        (is (= ["item-1" "item-2"] add-to-existing)))

      ;; Removing from selection
      (let [remove-item (filterv #(not= % "item-1") with-multiple)]
        (is (= ["item-2"] remove-item)))

      ;; Toggle logic simulation
      (let [toggle-fn (fn [selection item]
                        (if (some #{item} selection)
                          (filterv #(not= % item) selection)
                          (conj selection item)))]

        ;; Toggle add
        (is (= ["item-1"] (toggle-fn [] "item-1")))

        ;; Toggle remove
        (is (= [] (toggle-fn ["item-1"] "item-1")))

        ;; Toggle with multiple items
        (is (= ["item-2"] (toggle-fn ["item-1" "item-2"] "item-1")))))))

(deftest state-update-logic-test
  (testing "State update logic - pure functions"
    (let [initial-state mock-store-fixture]

      ;; Selection update
      (let [with-selection (assoc-in initial-state [:view :selection] ["para-1"])]
        (is (= ["para-1"] (get-in with-selection [:view :selection])))
        ;; Original unchanged
        (is (= [] (get-in initial-state [:view :selection]))))

      ;; Highlight update
      (let [with-highlight (assoc-in initial-state [:view :highlighted] #{"para-2"})]
        (is (= #{"para-2"} (get-in with-highlight [:view :highlighted])))
        ;; Original unchanged
        (is (= #{} (get-in initial-state [:view :highlighted]))))

      ;; Multiple updates
      (let [multi-update (-> initial-state
                             (assoc-in [:view :selection] ["para-1"])
                             (assoc-in [:view :highlighted] #{"para-2"}))]
        (is (= ["para-1"] (get-in multi-update [:view :selection])))
        (is (= #{"para-2"} (get-in multi-update [:view :highlighted])))))))

(deftest node-operations-test
  (testing "Node operations - pure data manipulation"
    (let [nodes (:nodes mock-store-fixture)]

      ;; Node lookup
      (is (= {:type :p :props {:text "First paragraph"}}
             (get nodes "para-1")))

      ;; Node existence check
      (is (contains? nodes "para-1"))
      (is (not (contains? nodes "missing-node")))

      ;; Add new node
      (let [new-node {:type :span :props {:text "New span"}}
            with-new-node (assoc nodes "span-1" new-node)]
        (is (= 5 (count with-new-node)))
        (is (= new-node (get with-new-node "span-1")))
        ;; Original unchanged
        (is (= 4 (count nodes))))

      ;; Filter nodes by type
      (let [p-nodes (filter #(= :p (:type (val %))) nodes)]
        (is (= 2 (count p-nodes))))

      ;; Transform node props
      (let [update-text-fn (fn [node]
                             (assoc-in node [:props :text]
                                       (str "Updated: " (get-in node [:props :text]))))
            updated-para (update-text-fn (get nodes "para-1"))]
        (is (= "Updated: First paragraph"
               (get-in updated-para [:props :text])))))))

(deftest hiccup-generation-test
  (testing "Hiccup generation - pure data structures"
    (let [simple (:simple mock-hiccup-fixtures)
          with-attrs (:with-attrs mock-hiccup-fixtures)
          nested (:nested mock-hiccup-fixtures)]

      ;; Basic hiccup structure validation
      (is (vector? simple))
      (is (keyword? (first simple)))
      (is (string? (second simple)))

      ;; Hiccup with attributes
      (is (vector? with-attrs))
      (is (keyword? (first with-attrs)))
      (is (map? (second with-attrs)))
      (is (string? (nth with-attrs 2)))

      ;; Nested hiccup
      (is (vector? nested))
      (is (map? (second nested)))
      (let [children (drop 2 nested)]
        (is (= 2 (count children)))
        (doseq [child children]
          (is (vector? child))
          (is (keyword? (first child))))))))

(deftest mock-dispatch-simulation-test
  (testing "Mock dispatch simulation - pure state changes"
    (let [initial-state mock-store-fixture

          ;; Mock intent functions
          toggle-selection-intent (fn [state target-id]
                                    (let [current-selection (get-in state [:view :selection])
                                          new-selection (if (some #{target-id} current-selection)
                                                          (filterv #(not= % target-id) current-selection)
                                                          (conj current-selection target-id))]
                                      (assoc-in state [:view :selection] new-selection)))

          highlight-intent (fn [state target-id]
                             (let [current-highlighted (get-in state [:view :highlighted])
                                   new-highlighted (if (contains? current-highlighted target-id)
                                                     (disj current-highlighted target-id)
                                                     (conj current-highlighted target-id))]
                               (assoc-in state [:view :highlighted] new-highlighted)))]

      ;; Test selection intent
      (let [after-select (toggle-selection-intent initial-state "para-1")]
        (is (= ["para-1"] (get-in after-select [:view :selection]))))

      ;; Test highlight intent
      (let [after-highlight (highlight-intent initial-state "para-2")]
        (is (= #{"para-2"} (get-in after-highlight [:view :highlighted]))))

      ;; Test combined intents
      (let [combined (-> initial-state
                         (toggle-selection-intent "para-1")
                         (highlight-intent "para-2"))]
        (is (= ["para-1"] (get-in combined [:view :selection])))
        (is (= #{"para-2"} (get-in combined [:view :highlighted]))))

      ;; Test deselection
      (let [selected-state (toggle-selection-intent initial-state "para-1")
            deselected (toggle-selection-intent selected-state "para-1")]
        (is (= [] (get-in deselected [:view :selection])))))))

(deftest data-validation-test
  (testing "Data structure validation - schema checking"
    ;; Valid state structure
    (let [valid-state mock-store-fixture]
      (is (map? (:nodes valid-state)))
      (is (map? (:children-by-parent valid-state)))
      (is (map? (:view valid-state)))
      (is (vector? (get-in valid-state [:view :selection])))
      (is (set? (get-in valid-state [:view :highlighted]))))

    ;; Valid node structure
    (let [valid-node mock-node-fixture]
      (is (keyword? (:type valid-node)))
      (is (map? (:props valid-node)))
      (is (string? (:id valid-node))))

    ;; Edge cases
    (let [empty-state {:nodes {}
                       :children-by-parent {}
                       :view {:selection [] :highlighted #{}}}]
      (is (empty? (:nodes empty-state)))
      (is (empty? (get-in empty-state [:view :selection]))))))

(deftest performance-simulation-test
  (testing "Performance with mock large datasets"
    ;; Create large mock dataset
    (let [large-nodes (into {} (for [i (range 100)]
                                 [(str "node-" i) {:type :p :props {:text (str "Node " i)}}]))
          large-selection (mapv #(str "node-" %) (range 0 50 2)) ; Every other node

          ;; Simulate operations on large dataset
          filter-selected (fn [nodes selection]
                            (filter #(some #{(key %)} selection) nodes))

          update-all-props (fn [nodes]
                             (into {} (map (fn [[k v]]
                                             [k (assoc-in v [:props :updated] true)])
                                           nodes)))]

      ;; Large dataset operations
      (is (= 100 (count large-nodes)))
      (is (= 25 (count large-selection)))

      ;; Filter operation
      (let [selected-nodes (filter-selected large-nodes large-selection)]
        (is (= 25 (count selected-nodes))))

      ;; Bulk update operation
      (let [updated-nodes (update-all-props large-nodes)]
        (is (= 100 (count updated-nodes)))
        (doseq [[_ node] updated-nodes]
          (is (true? (get-in node [:props :updated]))))))))

(deftest immutability-verification-test
  (testing "Immutability verification - ensure no mutations"
    (let [original-state mock-store-fixture
          original-nodes (:nodes original-state)
          original-selection (get-in original-state [:view :selection])]

      ;; Modify derived values
      (let [modified-state (assoc-in original-state [:view :selection] ["para-1"])
            modified-nodes (assoc original-nodes "new-node" {:type :span})]

        ;; Original structures should be unchanged
        (is (= [] (get-in original-state [:view :selection])))
        (is (= 4 (count original-nodes)))
        (is (= [] original-selection))

        ;; New structures should have changes
        (is (= ["para-1"] (get-in modified-state [:view :selection])))
        (is (= 5 (count modified-nodes)))))))

;; Run all pure logic tests
(run-tests)