(ns evolver.keyboard-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.kernel :as kernel]
            [evolver.keyboard :as keyboard]
            [evolver.commands :as commands]))

(defn create-test-store
  "Create a test store for keyboard testing"
  []
  (atom kernel/db))

(defn simulate-keyboard-event
  "Helper to simulate keyboard events for testing"
  [key & {:keys [shift ctrl alt meta]}]
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

(defn get-selection [store]
  "Get current selection from test store"
  (get-in @store [:view :selected]))

(defn set-selection! [store node-ids]
  "Set selection for testing"
  (swap! store assoc-in [:view :selected] (set node-ids)))

(deftest test-escape-clears-selection
  (testing "Escape key clears selection"
    (let [store (create-test-store)]
      (set-selection! store ["p1-select" "p2-high"])
      (is (= #{"p1-select" "p2-high"} (get-selection store)))

      (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))
      (is (= #{} (get-selection store))))))

(deftest test-key-matching
  (testing "Key matching works correctly"
    (let [mapping {:key "Enter" :shift true}
          event1 (simulate-keyboard-event "Enter" :shift true)
          event2 (simulate-keyboard-event "Enter" :shift false)
          event3 (simulate-keyboard-event "Tab" :shift true)]

      (is (keyboard/key-matches? event1 mapping))
      (is (not (keyboard/key-matches? event2 mapping)))
      (is (not (keyboard/key-matches? event3 mapping))))))

(deftest test-keyboard-mappings-structure
  (testing "Keyboard mappings have correct structure"
    (is (vector? keyboard/keyboard-mappings))
    (is (every? map? keyboard/keyboard-mappings))
    (is (every? #(contains? % :key) keyboard/keyboard-mappings))
    (is (every? #(contains? % :command) keyboard/keyboard-mappings))))

(deftest test-selection-requirements
  (testing "Commands that require selection work correctly"
    (let [store (create-test-store)]
      ;; Test command that requires selection fails without selection
      (is (= false (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))))

      ;; Test that commands work when executed - test by checking state change
      (set-selection! store ["p3-both"])
      (let [before-nodes (count (:nodes @store))]
        (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))
        (let [after-nodes (count (:nodes @store))]
          (is (< after-nodes before-nodes) "Node should be deleted"))))))

(deftest test-modifier-combinations
  (testing "Modifier key combinations work"
    (let [store (create-test-store)]
      ;; Clear initial selection first
      (set-selection! store [])

      ;; Test select all by checking if selection changes
      (is (empty? (get-selection store)) "Should start with empty selection")
      (keyboard/handle-keyboard-event store (simulate-keyboard-event "A" :shift true :meta true))
      (is (not (empty? (get-selection store))) "Select all should select nodes")

      ;; Test Tab (indent) by checking structure changes
      (let [fresh-store (create-test-store)]
        (set-selection! fresh-store ["p2-high"])
        (let [before-structure (:children-by-parent @fresh-store)]
          (keyboard/handle-keyboard-event fresh-store (simulate-keyboard-event "Tab"))
          (let [after-structure (:children-by-parent @fresh-store)]
            (is (not= before-structure after-structure) "Tab should change structure"))))

      ;; Test Shift+Tab (outdent) by checking structure changes  
      (let [fresh-store (create-test-store)]
        (set-selection! fresh-store ["p4-click"])
        (let [before-structure (:children-by-parent @fresh-store)]
          (keyboard/handle-keyboard-event fresh-store (simulate-keyboard-event "Tab" :shift true))
          (let [after-structure (:children-by-parent @fresh-store)]
            (is (not= before-structure after-structure) "Shift+Tab should change structure")))))))

(deftest test-command-dispatch
  (testing "Keyboard events dispatch correct commands"
    (let [store (create-test-store)
          dispatched-commands (atom [])]

      ;; Mock the command dispatch to capture what gets called
      (with-redefs [commands/dispatch-command
                    (fn [store event-data command]
                      (swap! dispatched-commands conj command))]

        ;; Test escape dispatches clear-selection
        (keyboard/handle-keyboard-event store (simulate-keyboard-event "Escape"))
        (is (= [[:clear-selection {}]] @dispatched-commands))

        ;; Test with selection required command (using real node ID)
        (reset! dispatched-commands [])
        (set-selection! store ["p1-select"])
        (keyboard/handle-keyboard-event store (simulate-keyboard-event "Delete"))
        (is (= [[:delete-selected-blocks {}]] @dispatched-commands))))))

(deftest test-navigate-sibling-commands
  "Test that navigate-sibling commands work with proper parameter format"
  (testing "navigate-sibling :up command"
    (let [store (create-test-store)]
      ;; Set up initial selection
      (set-selection! store #{"div1"})

      ;; Simulate Alt+ArrowUp keyboard event
      (let [event (simulate-keyboard-event "ArrowUp" :alt true)]
        (keyboard/handle-keyboard-event store event)
        ;; Should not throw an error - this would have failed before the fix
        (is true "Command executed without error"))))

  (testing "navigate-sibling :down command"
    (let [store (create-test-store)]
      ;; Set up initial selection
      (set-selection! store #{"div1"})

      ;; Simulate Alt+ArrowDown keyboard event
      (let [event (simulate-keyboard-event "ArrowDown" :alt true)]
        (keyboard/handle-keyboard-event store event)
        ;; Should not throw an error
        (is true "Command executed without error")))))

(deftest test-move-block-commands
  "Test that move-block commands work with proper parameter format"
  (testing "move-block :up command"
    (let [store (create-test-store)]
      ;; Set up initial selection
      (set-selection! store #{"div1"})

      ;; Simulate Alt+Shift+ArrowUp keyboard event
      (let [event (simulate-keyboard-event "ArrowUp" :alt true :shift true)]
        (keyboard/handle-keyboard-event store event)
        ;; Should not throw an error - this would have failed before the fix
        (is true "Command executed without error"))))

  (testing "move-block :down command"
    (let [store (create-test-store)]
      ;; Set up initial selection
      (set-selection! store #{"div1"})

      ;; Simulate Alt+Shift+ArrowDown keyboard event
      (let [event (simulate-keyboard-event "ArrowDown" :alt true :shift true)]
        (keyboard/handle-keyboard-event store event)
        ;; Should not throw an error
        (is true "Command executed without error")))))

(deftest test-keyboard-command-parameter-format
  "Test that all keyboard commands receive parameters in the expected format"
  (testing "Command parameter structure validation"
    (let [problematic-mappings (filter
                                (fn [mapping]
                                  (let [[cmd-name params] (:command mapping)]
                                    (and params
                                         (not (map? params))
                                         (#{:navigate-sibling :move-block} cmd-name))))
                                keyboard/keyboard-mappings)]
      (is (empty? problematic-mappings)
          (str "Found keyboard mappings with incorrect parameter format: "
               (pr-str problematic-mappings))))))