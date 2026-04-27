(ns kernel.state-machine-test
  "Tests for UI state machine (Logseq parity).

   These are pure unit tests - no browser needed.
   Tests state transitions and intent validation against the state machine."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [harness.intent-fixtures :as intent-fixtures]
            [kernel.state-machine :as sm]))

(use-fixtures :each intent-fixtures/with-state-machine-intents)

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(def idle-session
  "Session in background state (no focus, selection, or editing)."
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {}
   :ui {:folded #{}
        :zoom-root nil
        :editing-block-id nil
        :cursor-position nil}})

(def focused-session
  "Session in focus-only state (current block, no selected nodes, no editing)."
  (assoc-in idle-session [:selection :focus] "a"))

(def selection-session
  "Session in selection state (blocks selected, not editing)."
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{"a" "b"} :focus "b" :anchor "a"}
   :buffer {}
   :ui {:folded #{}
        :zoom-root nil
        :editing-block-id nil
        :cursor-position nil}})

(def editing-session
  "Session in editing state (one block being edited)."
  {:cursor {:block-id "a" :offset 5}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {"a" "hello world"}
   :ui {:folded #{}
        :zoom-root nil
        :editing-block-id "a"
        :cursor-position 5}})

;; ── State Detection Tests ───────────────────────────────────────────────────

;; current-state-test also verifies that editing takes precedence over
;; selection when both are set — a softer form of selection-clears-edit.
;; The stricter version (entering selection actively clears editing) is
;; covered by plugins/selection_test.cljc via the :selection intent.
(deftest ^{:fr/ids #{:fr.state/selection-clears-edit}} current-state-test
  (testing "Detects background state"
    (is (= :background (sm/current-state idle-session)))
    (is (sm/in-idle-state? idle-session))
    (is (sm/in-background-state? idle-session))
    (is (not (sm/in-selection-state? idle-session)))
    (is (not (sm/in-editing-state? idle-session))))

  (testing "Detects focused state"
    (is (= :focused (sm/current-state focused-session)))
    (is (sm/in-focused-state? focused-session))
    (is (not (sm/in-idle-state? focused-session)))
    (is (not (sm/in-selection-state? focused-session)))
    (is (not (sm/in-editing-state? focused-session))))

  (testing "Detects selection state"
    (is (= :selection (sm/current-state selection-session)))
    (is (sm/in-selection-state? selection-session))
    (is (not (sm/in-idle-state? selection-session)))
    (is (not (sm/in-editing-state? selection-session))))

  (testing "Detects editing state"
    (is (= :editing (sm/current-state editing-session)))
    (is (sm/in-editing-state? editing-session))
    (is (not (sm/in-idle-state? editing-session)))
    (is (not (sm/in-selection-state? editing-session))))

  (testing "Editing takes precedence over selection"
    ;; Edge case: both editing-block-id AND selection nodes set
    ;; This shouldn't happen, but if it does, editing wins
    (let [mixed-session (assoc-in editing-session [:selection :nodes] #{"b"})]
      (is (= :editing (sm/current-state mixed-session))))))

;; ── Idle State Guard Tests ──────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.state/idle-guard}} idle-guard-test
  (testing "Blocks editing intents in idle state"
    (is (sm/idle-guard idle-session {:type :enter-edit :block-id "a"}))
    (is (sm/idle-guard idle-session {:type :context-aware-enter :block-id "a"}))
    (is (sm/idle-guard idle-session {:type :delete :id "a"}))
    (is (sm/idle-guard idle-session {:type :merge-with-prev :block-id "a"}))
    (is (sm/idle-guard idle-session {:type :indent :id "a"})))

  (testing "Allows selection intents in idle state"
    (is (not (sm/idle-guard idle-session {:type :selection :mode :replace :ids "a"}))))

  (testing "Does not block intents when not in idle state"
    (is (not (sm/idle-guard focused-session {:type :enter-edit :block-id "a"})))
    (is (not (sm/idle-guard selection-session {:type :enter-edit :block-id "a"})))
    (is (not (sm/idle-guard editing-session {:type :delete :id "a"})))))

;; ── Intent Allowed Tests ────────────────────────────────────────────────────

(deftest intent-allowed-test
  (testing "Enter-edit allowed in focused and selection state"
    (is (not (sm/intent-allowed? idle-session {:type :enter-edit :block-id "a"})))
    (is (sm/intent-allowed? focused-session {:type :enter-edit :block-id "a"}))
    (is (sm/intent-allowed? selection-session {:type :enter-edit :block-id "a"}))
    (is (not (sm/intent-allowed? editing-session {:type :enter-edit :block-id "a"}))))

  (testing "Type-to-edit allowed in focused and selection state"
    (is (not (sm/intent-allowed? idle-session {:type :enter-edit-with-char :block-id "a" :char "x"})))
    (is (sm/intent-allowed? focused-session {:type :enter-edit-with-char :block-id "a" :char "x"}))
    (is (sm/intent-allowed? selection-session {:type :enter-edit-with-char :block-id "a" :char "x"})))

  (testing "Navigate intents only allowed in editing state"
    (is (not (sm/intent-allowed? idle-session
                                 {:type :navigate-with-cursor-memory
                                  :direction :up
                                  :current-block-id "a"})))
    (is (not (sm/intent-allowed? selection-session
                                 {:type :navigate-with-cursor-memory
                                  :direction :up
                                  :current-block-id "a"})))
    (is (sm/intent-allowed? editing-session
                            {:type :navigate-with-cursor-memory
                             :direction :up
                             :current-block-id "a"
                             :current-text "hello"
                             :current-cursor-pos 3})))

  (testing "Smart-split only allowed in editing state"
    (is (not (sm/intent-allowed? idle-session {:type :smart-split :block-id "a"})))
    (is (not (sm/intent-allowed? selection-session {:type :smart-split :block-id "a"})))
    (is (sm/intent-allowed? editing-session {:type :smart-split :block-id "a"})))

  (testing "Selection intent allowed in any state"
    (is (sm/intent-allowed? idle-session {:type :selection :mode :replace :ids "a"}))
    (is (sm/intent-allowed? selection-session {:type :selection :mode :extend :ids "b"}))
    (is (sm/intent-allowed? editing-session {:type :selection :mode :replace :ids "a"})))

  (testing "Undo/redo allowed in any state"
    (is (sm/intent-allowed? idle-session {:type :undo}))
    (is (sm/intent-allowed? selection-session {:type :undo}))
    (is (sm/intent-allowed? editing-session {:type :redo}))))

;; ── Validate Intent State Tests ─────────────────────────────────────────────

(deftest validate-intent-state-test
  (testing "Returns nil for valid intents"
    (is (nil? (sm/validate-intent-state selection-session
                                        {:type :enter-edit :block-id "a"})))
    (is (nil? (sm/validate-intent-state editing-session
                                        {:type :navigate-with-cursor-memory
                                         :direction :up
                                         :current-block-id "a"
                                         :current-text "hello"
                                         :current-cursor-pos 3}))))

  (testing "Returns error map for invalid intents"
    (let [error (sm/validate-intent-state idle-session
                                          {:type :enter-edit :block-id "a"})]
      (is (= :invalid-state-for-intent (:error error)))
      (is (= :enter-edit (:intent-type error)))
      (is (= :background (:current-state error)))
      (is (= #{:focused :selection} (:allowed-states error))))))

;; ── Allowed Intents Query Tests ─────────────────────────────────────────────

(deftest allowed-intents-test
  (testing "Returns correct intent set for idle state"
    (let [allowed (sm/allowed-intents idle-session)]
      ;; Selection should be allowed (no :allowed-states = any state)
      (is (contains? allowed :selection))
      ;; Note: :undo is not registered as an intent (handled by history module directly)
      ;; Enter-edit should NOT be allowed (requires :selection state)
      (is (not (contains? allowed :enter-edit)))))

  (testing "Returns correct intent set for editing state"
    (let [allowed (sm/allowed-intents editing-session)]
      (is (contains? allowed :navigate-with-cursor-memory))
      (is (contains? allowed :smart-split))
      (is (contains? allowed :selection))
      (is (contains? allowed :exit-edit)))))

;; ── Describe State Helper Tests ─────────────────────────────────────────────

(deftest describe-state-test
  (testing "Describes idle state"
    (let [{:keys [state description]} (sm/describe-state idle-session)]
      (is (= :background state))
      (is (string? description))
      (is (= "No block selected, focused, or editing" description))))

  (testing "Describes focused state"
    (let [{:keys [state details]} (sm/describe-state focused-session)]
      (is (= :focused state))
      (is (= "a" (:focus-id details)))))

  (testing "Describes selection state"
    (let [{:keys [state details]} (sm/describe-state selection-session)]
      (is (= :selection state))
      (is (= 2 (:selection-count details)))
      (is (= "b" (:focus-id details)))))

  (testing "Describes editing state"
    (let [{:keys [state details]} (sm/describe-state editing-session)]
      (is (= :editing state))
      (is (= "a" (:editing-block-id details))))))

;; ── Transition Tests ────────────────────────────────────────────────────────

(deftest transition-test
  (testing "Valid transitions from background"
    (is (= :selection (sm/get-next-state :background :selection)))
    (is (= :selection (sm/get-next-state :background :arrow-up)))
    (is (= :selection (sm/get-next-state :background :arrow-down))))

  (testing "Valid transitions from focused"
    (is (= :editing (sm/get-next-state :focused :enter-edit)))
    (is (= :editing (sm/get-next-state :focused :enter-edit-with-char)))
    (is (= :background (sm/get-next-state :focused :background-click))))

  (testing "Valid transitions from selection"
    (is (= :selection (sm/get-next-state :selection :selection)))
    (is (= :editing (sm/get-next-state :selection :enter-edit)))
    (is (= :idle (sm/get-next-state :selection :exit-edit)))
    (is (= :idle (sm/get-next-state :selection :background-click))))

  (testing "Valid transitions from editing"
    (is (= :selection (sm/get-next-state :editing :exit-edit)))
    (is (= :selection (sm/get-next-state :editing :selection)))
    (is (= :idle (sm/get-next-state :editing :blur))))

  (testing "Invalid transitions return nil"
    ;; Can't enter edit from idle directly (need selection first)
    ;; Actually, looking at the transitions, this IS allowed via type-to-edit
    ;; Let me check invalid ones
    (is (nil? (sm/get-next-state :background :blur)))
    (is (nil? (sm/get-next-state :selection :blur)))))
