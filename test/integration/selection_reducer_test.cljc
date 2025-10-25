(ns integration.selection-reducer-test
  "Tests for unified :selection intent with modes.

   Tests that the single :selection reducer covers all previous selection behaviors.
   This locks down the Step 2 refactor."
  (:require [clojure.test :refer [deftest is testing]]
            [test-helper :as helper]
            [kernel.api :as api]
            [kernel.query :as q]))

(deftest selection-mode-replace
  (testing ":replace mode sets selection (replacing previous)"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "b"})]
      (is (= #{"b"} (q/selection db))
          "Selection should contain only 'b'")
      (is (= "b" (q/focus db))
          "Focus should be 'b'"))))

(deftest selection-mode-extend-multiple
  (testing ":extend mode with multiple IDs adds to selection (no range)"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          ;; Extend with multiple IDs - should not trigger range selection
          {:keys [db]} (api/dispatch db {:type :selection :mode :extend :ids ["c" "d"]})]
      (is (= #{"a" "c" "d"} (q/selection db))
          "Selection should contain a, c, and d (no range)")
      (is (= "d" (q/focus db))
          "Focus should be last extended node"))))

(deftest selection-mode-extend-range
  (testing ":extend mode with range selection (doc range between anchor and new focus)"
    (let [db0 (helper/demo-db)
          ;; Start with 'a' selected (anchor = a)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          ;; Extend to 'c' should select doc range [a, b, c]
          {:keys [db]} (api/dispatch db {:type :selection :mode :extend :ids "c"})]
      (is (= #{"a" "b" "c"} (q/selection db))
          "Extend should select doc range from anchor to new focus")
      (is (= "c" (q/focus db))
          "Focus should be 'c'"))))

(deftest selection-mode-deselect
  (testing ":deselect mode removes from selection"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids ["a" "b" "c"]})
          {:keys [db]} (api/dispatch db {:type :selection :mode :deselect :ids "b"})]
      (is (= #{"a" "c"} (q/selection db))
          "Selection should have 'b' removed")
      (is (= "c" (q/focus db))
          "Focus should remain if not deselected"))))

(deftest selection-mode-deselect-focus
  (testing ":deselect mode reassigns focus if focus node is removed"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids ["a" "b" "c"]})
          ;; Deselect 'c' which is currently focus
          {:keys [db]} (api/dispatch db {:type :selection :mode :deselect :ids "c"})]
      (is (= #{"a" "b"} (q/selection db))
          "Selection should have 'c' removed")
      (is (contains? #{"a" "b"} (q/focus db))
          "Focus should reassign to one of remaining nodes"))))

(deftest selection-mode-toggle
  (testing ":toggle mode adds if not selected, removes if selected"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          ;; Toggle 'b' (not selected) - should add
          {:keys [db]} (api/dispatch db {:type :selection :mode :toggle :ids "b"})]
      (is (= #{"a" "b"} (q/selection db))
          "Toggle on unselected node should add it")

      ;; Toggle 'b' again (now selected) - should remove
      (let [{:keys [db]} (api/dispatch db {:type :selection :mode :toggle :ids "b"})]
        (is (= #{"a"} (q/selection db))
            "Toggle on selected node should remove it")))))

(deftest selection-mode-clear
  (testing ":clear mode removes all selection"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids ["a" "b" "c"]})
          {:keys [db]} (api/dispatch db {:type :selection :mode :clear})]
      (is (empty? (q/selection db))
          "Selection should be empty")
      (is (nil? (q/focus db))
          "Focus should be nil"))))

(deftest selection-mode-next
  (testing ":next mode selects next sibling"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          {:keys [db]} (api/dispatch db {:type :selection :mode :next})]
      (is (= #{"b"} (q/selection db))
          "Selection should move to next sibling")
      (is (= "b" (q/focus db))
          "Focus should be 'b'"))))

(deftest selection-mode-prev
  (testing ":prev mode selects previous sibling"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "c"})
          {:keys [db]} (api/dispatch db {:type :selection :mode :prev})]
      (is (= #{"b"} (q/selection db))
          "Selection should move to previous sibling")
      (is (= "b" (q/focus db))
          "Focus should be 'b'"))))

(deftest selection-mode-parent
  (testing ":parent mode selects parent of selection"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "d1"})
          {:keys [db]} (api/dispatch db {:type :selection :mode :parent})]
      (is (= #{"d"} (q/selection db))
          "Selection should be parent of 'd1'")
      (is (= "d" (q/focus db))
          "Focus should be 'd'"))))

(deftest selection-mode-all-siblings
  (testing ":all-siblings mode selects all siblings of focus"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "b"})
          {:keys [db]} (api/dispatch db {:type :selection :mode :all-siblings})]
      (is (= #{"a" "b" "c" "d"} (q/selection db))
          "Selection should include all siblings under 'page'")
      (is (= "d" (q/focus db))
          "Focus should be last sibling"))))

(deftest legacy-select-forwards-to-selection
  (testing "Legacy :select intent forwards to :selection :mode :replace"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :select :ids "b"})]
      (is (= #{"b"} (q/selection db))
          "Legacy :select should work via :selection")
      (is (= "b" (q/focus db))
          "Focus should be set"))))

(deftest legacy-extend-selection-forwards
  (testing "Legacy :extend-selection forwards to :selection :mode :extend"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :select :ids "a"})
          {:keys [db]} (api/dispatch db {:type :extend-selection :ids "c"})]
      (is (= #{"a" "b" "c"} (q/selection db))
          "Legacy :extend-selection should work via :selection range"))))

(deftest selection-multiple-ops-composable
  (testing "Multiple selection operations compose correctly"
    (let [db0 (helper/demo-db)
          ;; Build up selection using different modes
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          ;; Extend with multiple IDs to avoid range selection
          {:keys [db]} (api/dispatch db {:type :selection :mode :extend :ids ["b" "c"]})
          {:keys [db]} (api/dispatch db {:type :selection :mode :deselect :ids "a"})]
      (is (= #{"b" "c"} (q/selection db))
          "Composed selection operations should produce correct final state")
      (is (= "c" (q/focus db))
          "Focus should track last operation"))))
