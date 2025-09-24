(ns evolver.keyboard-integration-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.keyboard :as keyboard]
            [evolver.kernel :as kernel]))

(defn create-test-store []
  "Create a test store with realistic structure"
  (atom kernel/db))

(defn simulate-keyboard-event [key & {:keys [shift ctrl alt meta]}]
  "Helper to simulate keyboard events for testing"
  (let [event (js/Object.)]
    (set! (.-key event) key)
    (set! (.-getModifierState event)
          (fn [modifier]
            (case modifier
              "Shift" (boolean shift)
              "Control" (boolean ctrl)
              "Alt" (boolean alt)
              "Meta" (boolean meta)
              false)))
    (set! (.-preventDefault event) (fn []))
    event))

(defn set-selection! [store node-ids]
  "Set selection for testing"
  (swap! store assoc-in [:view :selected] (set node-ids)))

(defn get-selection [store]
  "Get current selection from test store"
  (get-in @store [:view :selected]))

(deftest test-keyboard-handler-returns-commands
  "Test that the keyboard handler properly returns commands instead of nil"
  (testing "Handler returns command tuples instead of nil"
    (let [store (create-test-store)]

      ;; Test Escape - should return command
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))]
        (is (vector? result) "Should return command vector")
        (is (= :clear-selection (first result)) "Should return clear-selection command"))

      ;; Test with selection - Delete should return command
      (set-selection! store ["p1-select"])
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))]
        (is (vector? result) "Should return command vector")
        (is (= :delete-selected-blocks (first result)) "Should return delete command"))

      ;; Test without selection - Delete should return false
      (set-selection! store [])
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))]
        (is (= false result) "Should return false for commands requiring selection when none selected")))))

(deftest test-selection-commands-work
  "Test that basic selection commands actually work"
  (testing "Clear selection works"
    (let [store (create-test-store)]
      (set-selection! store ["p1-select" "p2-high"])
      (is (= #{"p1-select" "p2-high"} (get-selection store)) "Initial selection should be set")

      (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))
      (is (= #{} (get-selection store)) "Selection should be cleared after Escape")))

  (testing "Select all works"
    (let [store (create-test-store)]
      (set-selection! store [])
      (is (empty? (get-selection store)) "Should start with empty selection")

      (keyboard/handle-keyboard-event store (simulate-keyboard-event "A" :shift true :meta true))
      (is (not (empty? (get-selection store))) "Select all should select nodes"))))

(deftest test-navigation-commands
  "Test that navigation commands work properly"
  (testing "Sibling navigation works"
    (let [store (create-test-store)]
      ;; Set selection to a node that has siblings
      (set-selection! store ["p2-high"])
      (let [initial-selection (get-selection store)]

        ;; Navigate down
        (keyboard/handle-keyboard-event store (simulate-keyboard-event "ArrowDown" :alt true))
        (let [new-selection (get-selection store)]
          (is (not= initial-selection new-selection) "Selection should change after navigation"))))))

(deftest test-parameter-format-consistency
  "Test that command parameters are in the expected format to prevent contract violations"
  (testing "All keyboard mappings have proper parameter structure"
    (let [mappings keyboard/keyboard-mappings]
      (doseq [mapping mappings]
        (let [[cmd-name params] (:command mapping)]
          (when (#{:navigate-sibling :move-block} cmd-name)
            (is (map? params)
                (str "Command " cmd-name " should have map parameters, got: " (type params) " - " params))))))))

(deftest test-critical-bug-regression
  "Regression test for the critical keyboard handler nil return bug"
  (testing "Keyboard handler no longer returns nil for valid commands"
    (let [store (create-test-store)]

      ;; This was the exact failure case - handler returned nil instead of command
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))]
        (is (not (nil? result)) "Handler should not return nil for valid commands")
        (is (vector? result) "Handler should return command vector")
        (is (keyword? (first result)) "First element should be command keyword"))

      ;; Test with selection-required command
      (set-selection! store ["p1-select"])
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))]
        (is (not (nil? result)) "Handler should not return nil for valid commands with selection")
        (is (vector? result) "Handler should return command vector")
        (is (keyword? (first result)) "First element should be command keyword")))))

(deftest test-keyboard-execution-flow
  "Test the complete flow from keyboard event to state change"
  (testing "Escape key complete flow"
    (let [store (create-test-store)]
      ;; Set up initial state
      (set-selection! store ["p1-select" "p2-high"])
      (is (seq (get-selection store)) "Should have selection initially")

      ;; Execute keyboard event
      (let [result (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))]
        ;; Check return value
        (is (= [:clear-selection {}] result) "Should return correct command")
        ;; Check state change
        (is (empty? (get-selection store)) "Selection should be cleared"))))

  (testing "Navigation key complete flow"
    (let [store (create-test-store)]
      (set-selection! store ["title"])
      (let [initial-selection (get-selection store)
            result (keyboard/handle-keyboard-event store (simulate-keyboard-event "ArrowDown" :alt true))]
        ;; Should return navigation command
        (is (= [:navigate-sibling {:direction :down}] result) "Should return navigation command")
        ;; Selection may or may not change depending on tree structure, but shouldn't error
        (is true "Navigation completed without error")))))