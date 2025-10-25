(ns integration.keymap-test
  "Tests for unified keymap resolver.

   Tests that keyboard events resolve to correct intent maps.
   This locks down the Step 3 refactor."
  (:require [clojure.test :refer [deftest is testing]]
            [test-helper :as helper]
            [keymap.core :as keymap]))

(deftest resolve-event-non-editing-context
  (testing "Non-editing context resolves arrow keys to selection"
    (let [db (helper/demo-db)
          event-down {:key "ArrowDown" :mod false :shift false :alt false}
          event-up {:key "ArrowUp" :mod false :shift false :alt false}]
      (is (= {:type :selection :mode :next}
             (keymap/resolve-event event-down db))
          "ArrowDown should resolve to :selection :mode :next")
      (is (= {:type :selection :mode :prev}
             (keymap/resolve-event event-up db))
          "ArrowUp should resolve to :selection :mode :prev"))))

(deftest resolve-event-tab-indentation
  (testing "Tab keys resolve to indent/outdent"
    (let [db (helper/demo-db)
          event-tab {:key "Tab" :mod false :shift false :alt false}
          event-shift-tab {:key "Tab" :mod false :shift true :alt false}]
      (is (= {:type :indent-selected}
             (keymap/resolve-event event-tab db))
          "Tab should resolve to :indent-selected")
      (is (= {:type :outdent-selected}
             (keymap/resolve-event event-shift-tab db))
          "Shift+Tab should resolve to :outdent-selected"))))

(deftest resolve-event-backspace-delete
  (testing "Backspace in non-editing mode deletes"
    (let [db (helper/demo-db)
          event {:key "Backspace" :mod false :shift false :alt false}]
      (is (= {:type :delete-selected}
             (keymap/resolve-event event db))
          "Backspace should resolve to :delete-selected"))))

(deftest resolve-event-enter-create
  (testing "Enter in non-editing mode creates new block"
    (let [db (helper/demo-db)
          event {:key "Enter" :mod false :shift false :alt false}]
      (is (= {:type :create-new-block-after-focus}
             (keymap/resolve-event event db))
          "Enter should resolve to :create-new-block-after-focus"))))

(deftest resolve-event-editing-context
  (testing "Editing context has different bindings"
    (let [db0 (helper/demo-db)
          ;; Enter edit mode
          db (helper/dispatch* db0 {:type :enter-edit :block-id "a"})
          event-esc {:key "Escape" :mod false :shift false :alt false}
          event-mod-backspace {:key "Backspace" :mod true :shift false :alt false}]
      (is (= {:type :exit-edit}
             (keymap/resolve-event event-esc db))
          "Escape in editing mode should resolve to :exit-edit")
      (is (= {:type :merge-with-prev}
             (keymap/resolve-event event-mod-backspace db))
          "Cmd/Ctrl+Backspace in editing mode should resolve to :merge-with-prev"))))

(deftest resolve-event-global-bindings
  (testing "Global bindings work in both contexts"
    (let [db0 (helper/demo-db)
          db-editing (helper/dispatch* db0 {:type :enter-edit :block-id "a"})
          event-move-up {:key "ArrowUp" :mod true :shift true :alt false}
          event-move-down {:key "ArrowDown" :mod true :shift true :alt false}]
      ;; Test in non-editing context
      (is (= {:type :move-selected-up}
             (keymap/resolve-event event-move-up db0))
          "Cmd+Shift+ArrowUp should resolve to :move-selected-up (non-editing)")
      (is (= {:type :move-selected-down}
             (keymap/resolve-event event-move-down db0))
          "Cmd+Shift+ArrowDown should resolve to :move-selected-down (non-editing)")

      ;; Test in editing context (global should still work)
      (is (= {:type :move-selected-up}
             (keymap/resolve-event event-move-up db-editing))
          "Cmd+Shift+ArrowUp should work in editing mode (global)")
      (is (= {:type :move-selected-down}
             (keymap/resolve-event event-move-down db-editing))
          "Cmd+Shift+ArrowDown should work in editing mode (global)"))))

(deftest resolve-event-no-match
  (testing "Unbound keys return nil"
    (let [db (helper/demo-db)
          event {:key "F1" :mod false :shift false :alt false}]
      (is (nil? (keymap/resolve-event event db))
          "Unbound key should return nil"))))

(deftest resolve-event-modifier-specificity
  (testing "Modifiers must match exactly"
    (let [db (helper/demo-db)
          ;; Tab without shift -> indent
          event-tab {:key "Tab" :mod false :shift false :alt false}
          ;; Tab with shift -> outdent
          event-shift-tab {:key "Tab" :mod false :shift true :alt false}
          ;; Tab with mod (unbound) -> nil
          event-mod-tab {:key "Tab" :mod true :shift false :alt false}]
      (is (= {:type :indent-selected}
             (keymap/resolve-event event-tab db))
          "Tab without modifiers should match indent")
      (is (= {:type :outdent-selected}
             (keymap/resolve-event event-shift-tab db))
          "Tab with shift should match outdent")
      (is (nil? (keymap/resolve-event event-mod-tab db))
          "Tab with mod should not match (unbound)"))))

(deftest resolve-intent-type-legacy
  (testing "Legacy resolve-intent-type returns just the :type"
    (let [db (helper/demo-db)
          event {:key "ArrowDown" :mod false :shift false :alt false}]
      (is (= :selection
             (keymap/resolve-intent-type event db))
          "Legacy function should return just :type keyword"))))

(deftest bindings-table-visible
  (testing "Bindings table is visible and complete"
    (is (map? keymap/bindings)
        "Bindings should be a map")
    (is (contains? keymap/bindings :non-editing)
        "Bindings should have :non-editing context")
    (is (contains? keymap/bindings :editing)
        "Bindings should have :editing context")
    (is (contains? keymap/bindings :global)
        "Bindings should have :global context")
    ;; Verify bindings are vectors of [key-spec intent-map] pairs
    (is (vector? (:non-editing keymap/bindings))
        ":non-editing bindings should be a vector")
    (is (every? (fn [[key-spec intent-map]]
                  (and (map? key-spec)
                       (contains? key-spec :key)
                       (map? intent-map)
                       (contains? intent-map :type)))
                (:non-editing keymap/bindings))
        "Each binding should be [key-spec intent-map]")))
