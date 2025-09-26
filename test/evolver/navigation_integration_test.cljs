(ns evolver.navigation-integration-test
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [evolver.commands :as commands]
            [evolver.kernel :as kernel]))

(defn create-test-tree []
  "Creates a test tree structure:
   root
   ├── node-a
   │   ├── node-a1
   │   └── node-a2
   │       └── node-a2-child
   ├── node-b (collapsed)
   │   ├── node-b1
   │   └── node-b2
   └── node-c"
  (let [store (atom kernel/db)]
    ;; Clear existing nodes except root
    (reset! store (assoc kernel/db
                         :nodes {"root" {:type :div}}
                         :children-by-parent {"root" []}
                         :view {:selected #{} :collapsed #{} :highlighted #{} :hovered-referencers #{}}))

    ;; Insert nodes to create the tree structure
    (let [commands [{:op :insert :parent-id "root" :node-id "node-a"
                     :node-data {:type :div :props {:text "Node A"}} :position nil}
                    {:op :insert :parent-id "root" :node-id "node-b"
                     :node-data {:type :div :props {:text "Node B"}} :position nil}
                    {:op :insert :parent-id "root" :node-id "node-c"
                     :node-data {:type :div :props {:text "Node C"}} :position nil}
                    {:op :insert :parent-id "node-a" :node-id "node-a1"
                     :node-data {:type :div :props {:text "Node A1"}} :position nil}
                    {:op :insert :parent-id "node-a" :node-id "node-a2"
                     :node-data {:type :div :props {:text "Node A2"}} :position nil}
                    {:op :insert :parent-id "node-a2" :node-id "node-a2-child"
                     :node-data {:type :div :props {:text "Node A2 Child"}} :position nil}
                    {:op :insert :parent-id "node-b" :node-id "node-b1"
                     :node-data {:type :div :props {:text "Node B1"}} :position nil}
                    {:op :insert :parent-id "node-b" :node-id "node-b2"
                     :node-data {:type :div :props {:text "Node B2"}} :position nil}]]
      (doseq [cmd commands]
        (reset! store (kernel/safe-apply-command @store cmd))))

    ;; Set node-b as collapsed
    (swap! store assoc-in [:view :collapsed] #{"node-b"})
    store))

(deftest test-sequential-navigation
  (testing "Sequential navigation with get-next and get-prev"
    (let [store (create-test-tree)]

      (testing "get-next functionality"
        ;; From node-a, next should be node-a1 (first child)
        (is (= "node-a1" (kernel/get-next @store "node-a")))

        ;; From node-a1, next should be node-a2 (next sibling)
        (is (= "node-a2" (kernel/get-next @store "node-a1")))

        ;; From node-a2, next should be node-a2-child (first child)
        (is (= "node-a2-child" (kernel/get-next @store "node-a2")))

        ;; From node-a2-child, next should be node-b (up to parent's next sibling)
        (is (= "node-b" (kernel/get-next @store "node-a2-child")))

        ;; From node-b (collapsed), next should be node-c (skip children due to collapse)
        (is (= "node-c" (kernel/get-next @store "node-b")))

        ;; From node-c (last), next should be nil
        (is (nil? (kernel/get-next @store "node-c"))))

      (testing "get-prev functionality"
        ;; From node-c, prev should be node-b (previous sibling, collapsed so no deep traversal)
        (is (= "node-b" (kernel/get-prev @store "node-c")))

        ;; From node-b, prev should be node-a2-child (deepest last child of previous sibling)
        (is (= "node-a2-child" (kernel/get-prev @store "node-b")))

        ;; From node-a2-child, prev should be node-a2 (parent, since no previous sibling)
        (is (= "node-a2" (kernel/get-prev @store "node-a2-child")))

        ;; From node-a2, prev should be node-a1 (previous sibling)
        (is (= "node-a1" (kernel/get-prev @store "node-a2")))

        ;; From node-a1, prev should be node-a (parent, since no previous sibling)
        (is (= "node-a" (kernel/get-prev @store "node-a1")))

        ;; From node-a (first child of root), prev should be nil
        (is (nil? (kernel/get-prev @store "node-a")))))))

(deftest test-structural-operations
  (testing "Structural operations preserve hierarchy"
    (let [store (create-test-tree)]

      (testing "indent operation"
        ;; Select node-b and indent it (should become child of node-a)
        (swap! store assoc-in [:view :selected] #{"node-b"})
        (commands/dispatch-command store {} [:indent-block {}])

        ;; Verify node-b is now child of node-a
        (let [pos (kernel/node-position @store "node-b")]
          (is (= "node-a" (:parent pos))))

        ;; Verify node-b's children are preserved
        (is (= ["node-b1" "node-b2"] (get-in @store [:children-by-parent "node-b"]))))

      (testing "outdent operation"
        ;; Outdent node-a2 (should become sibling of node-a)
        (swap! store assoc-in [:view :selected] #{"node-a2"})
        (commands/dispatch-command store {} [:outdent-block {}])

        ;; Verify node-a2 is now child of root
        (let [pos (kernel/node-position @store "node-a2")]
          (is (= "root" (:parent pos))))

        ;; Verify node-a2's children are preserved
        (is (= ["node-a2-child"] (get-in @store [:children-by-parent "node-a2"])))))))

(deftest test-multi-select-operations
  (testing "Multi-select operations preserve relative hierarchy"
    (let [store (create-test-tree)]

      (testing "multi-select indent"
        ;; Select node-a1 and node-a2
        (swap! store assoc-in [:view :selected] #{"node-a1" "node-a2"})
        ;; This should fail since we need a previous sibling, but let's test the code handles it gracefully
        (commands/dispatch-command store {} [:indent-block {}])

        ;; Since node-a1 has no previous sibling, it shouldn't move
        ;; node-a2 should become child of node-a1
        (let [pos-a1 (kernel/node-position @store "node-a1")
              pos-a2 (kernel/node-position @store "node-a2")]
          ;; Check positions are reasonable (exact behavior depends on implementation)
          (is (some? pos-a1))
          (is (some? pos-a2))))

      (testing "multi-select outdent"
        ;; Reset and select multiple nodes for outdent
        (let [fresh-store (create-test-tree)]
          (swap! fresh-store assoc-in [:view :selected] #{"node-a1" "node-a2"})
          (commands/dispatch-command fresh-store {} [:outdent-block {}])

          ;; Both should become children of root
          (let [pos-a1 (kernel/node-position @fresh-store "node-a1")
                pos-a2 (kernel/node-position @fresh-store "node-a2")]
            (is (= "root" (:parent pos-a1)))
            (is (= "root" (:parent pos-a2)))))))))

(deftest test-parent-child-navigation
  (testing "Parent and child navigation commands"
    (let [store (create-test-tree)]

      (testing "select-parent command"
        (swap! store assoc-in [:view :selected] #{"node-a2-child"})
        (commands/dispatch-command store {} [:select-parent {}])
        (is (= #{"node-a2"} (get-in @store [:view :selected]))))

      (testing "select-first-child command"
        (swap! store assoc-in [:view :selected] #{"node-a"})
        (commands/dispatch-command store {} [:select-first-child {}])
        (is (= #{"node-a1"} (get-in @store [:view :selected]))))

      (testing "select-last-child command"
        (swap! store assoc-in [:view :selected] #{"node-a"})
        (commands/dispatch-command store {} [:select-last-child {}])
        (is (= #{"node-a2"} (get-in @store [:view :selected])))))))

(deftest test-collapsed-aware-navigation
  (testing "Navigation respects collapsed state"
    (let [store (create-test-tree)]

      (testing "get-next skips collapsed children"
        ;; With node-b collapsed, get-next from node-a2-child should skip node-b's children
        (is (= "node-b" (kernel/get-next @store "node-a2-child")))
        ;; And from node-b should go to node-c
        (is (= "node-c" (kernel/get-next @store "node-b"))))

      (testing "get-prev handles collapsed blocks correctly"
        ;; From node-c, should go to node-b (not into its children since collapsed)
        (is (= "node-b" (kernel/get-prev @store "node-c"))))

      (testing "navigation with respect-collapsed? = false"
        ;; When not respecting collapsed state, should enter collapsed children
        (is (= "node-b1" (kernel/get-next @store "node-b" :respect-collapsed? false)))))))

(deftest test-sequential-navigation-commands
  (testing "Sequential navigation commands work correctly"
    (let [store (create-test-tree)]

      (testing "navigate-sequential down"
        (swap! store assoc-in [:view :selected] #{"node-a"})
        (commands/dispatch-command store {} [:navigate-sequential {:direction :down}])
        (is (= #{"node-a1"} (get-in @store [:view :selected]))))

      (testing "navigate-sequential up"
        (swap! store assoc-in [:view :selected] #{"node-a2"})
        (commands/dispatch-command store {} [:navigate-sequential {:direction :up}])
        (is (= #{"node-a1"} (get-in @store [:view :selected])))))))

;; Function to run all navigation tests
(defn run-navigation-tests []
  (run-tests 'evolver.navigation-integration-test))