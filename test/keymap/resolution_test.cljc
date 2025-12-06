(ns keymap.resolution-test
  "Tests for keymap resolution: DOM event → intent type.

   These tests verify that key combinations actually resolve to the correct intents.
   This is critical because integration tests often skip the keymap layer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [keymap.core :as keymap]
            [keymap.bindings :as bindings]))

;; ── Fixture: Ensure bindings are loaded ──────────────────────────────────────

(defn ensure-bindings-loaded [f]
  (bindings/reload!)
  (f))

(use-fixtures :once ensure-bindings-loaded)

;; ── Helper to simulate key events ────────────────────────────────────────────

(defn make-event
  "Create a key event map for testing."
  [event-map]
  {:key (:key event-map)
   :mod (get event-map :mod false)
   :shift (get event-map :shift false)
   :alt (get event-map :alt false)})

;; ── Non-Editing Mode Tests ───────────────────────────────────────────────────

(deftest tab-indent-non-editing
  (testing "Tab in non-editing mode → :indent-selected"
    (let [session {:ui {:editing-block-id nil}}
          event (make-event {:key "Tab"})
          intent (keymap/resolve-intent-type event session)]
      (is (= :indent-selected intent)
          "Tab should resolve to :indent-selected in non-editing mode"))))

(deftest shift-tab-outdent-non-editing
  (testing "Shift+Tab in non-editing mode → :outdent-selected"
    (let [session {:ui {:editing-block-id nil}}
          event (make-event {:key "Tab" :shift true})
          intent (keymap/resolve-intent-type event session)]
      (is (= :outdent-selected intent)
          "Shift+Tab should resolve to :outdent-selected in non-editing mode"))))

;; ── Editing Mode Tests ───────────────────────────────────────────────────────

(deftest tab-indent-editing
  (testing "Tab in editing mode → :indent-selected"
    (let [session {:ui {:editing-block-id "some-block"}}
          event (make-event {:key "Tab"})
          intent (keymap/resolve-intent-type event session)]
      (is (= :indent-selected intent)
          "Tab should resolve to :indent-selected in editing mode"))))

(deftest shift-tab-outdent-editing
  (testing "Shift+Tab in editing mode → :outdent-selected"
    (let [session {:ui {:editing-block-id "some-block"}}
          event (make-event {:key "Tab" :shift true})
          intent (keymap/resolve-intent-type event session)]
      (is (= :outdent-selected intent)
          "Shift+Tab should resolve to :outdent-selected in editing mode"))))

;; ── Arrow Navigation Tests ───────────────────────────────────────────────────

(deftest arrow-navigation-non-editing
  (testing "Arrow keys in non-editing mode → selection navigation"
    (let [session {:ui {:editing-block-id nil}}]
      (is (= {:type :selection :mode :next}
             (keymap/resolve-intent-type (make-event {:key "ArrowDown"}) session))
          "ArrowDown → select next")
      (is (= {:type :selection :mode :prev}
             (keymap/resolve-intent-type (make-event {:key "ArrowUp"}) session))
          "ArrowUp → select prev"))))

;; ── Modifier Key Tests ───────────────────────────────────────────────────────

(deftest undo-redo-bindings
  (testing "Undo/Redo key bindings"
    (let [session {:ui {:editing-block-id nil}}]
      (is (= :undo
             (keymap/resolve-intent-type (make-event {:key "z" :mod true}) session))
          "Cmd+Z → undo")
      (is (= :redo
             (keymap/resolve-intent-type (make-event {:key "z" :mod true :shift true}) session))
          "Cmd+Shift+Z → redo"))))

(deftest move-block-bindings
  (testing "Move block key bindings"
    (let [session {:ui {:editing-block-id nil}}]
      (is (= :move-selected-up
             (keymap/resolve-intent-type (make-event {:key "ArrowUp" :mod true :shift true}) session))
          "Cmd+Shift+Up → move up")
      (is (= :move-selected-down
             (keymap/resolve-intent-type (make-event {:key "ArrowDown" :mod true :shift true}) session))
          "Cmd+Shift+Down → move down"))))
