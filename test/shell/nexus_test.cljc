(ns shell.nexus-test
  "Unit tests for Nexus action pipeline.
   
   Tests what we care about:
   - Actions are pure: same input → same output
   - Actions return valid effect lists
   - Effects correctly dispatch intents to kernel
   - Single dispatch per event (no double-dispatch bugs)"
  (:require [clojure.test :refer [deftest testing is]]
            [shell.nexus :as nexus]
            [kernel.db :as DB]
            [kernel.transaction :as tx]
            [kernel.api :as api]))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn sample-db
  "Create a simple DB for testing actions."
  []
  (-> (DB/empty-db)
      (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "first block"}}
                     {:op :place :id "a" :under :doc :at :last}
                     {:op :create-node :id "b" :type :block :props {:text "second block"}}
                     {:op :place :id "b" :under :doc :at :last}
                     {:op :create-node :id "c" :type :block :props {:text "third block"}}
                     {:op :place :id "c" :under :doc :at :last}])
      :db))

;; ── Action Purity Tests ───────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-up-is-pure
  (testing "navigate-up action is pure (same input → same output)"
    (let [state (sample-db)
          payload {:block-id "b" :cursor-row :first}
          result1 (nexus/navigate-up state payload)
          result2 (nexus/navigate-up state payload)]
      (is (= result1 result2)
          "Multiple calls with same args should return identical results"))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-down-is-pure
  (testing "navigate-down action is pure"
    (let [state (sample-db)
          payload {:block-id "b" :cursor-row :last}
          result1 (nexus/navigate-down state payload)
          result2 (nexus/navigate-down state payload)]
      (is (= result1 result2)))))

(deftest ^{:fr/ids #{:fr.selection/extend-boundary}}
  extend-selection-prev-is-pure
  (testing "extend-selection-prev action is pure"
    (let [state (sample-db)
          payload {:block-id "b" :direction :backward}
          result1 (nexus/extend-selection-prev state payload)
          result2 (nexus/extend-selection-prev state payload)]
      (is (= result1 result2)))))

(deftest ^{:fr/ids #{:fr.selection/extend-boundary}}
  extend-selection-next-is-pure
  (testing "extend-selection-next action is pure"
    (let [state (sample-db)
          payload {:block-id "b" :direction :forward}
          result1 (nexus/extend-selection-next state payload)
          result2 (nexus/extend-selection-next state payload)]
      (is (= result1 result2)))))

(deftest ^{:fr/ids #{:fr.edit/smart-split}}
  smart-split-is-pure
  (testing "smart-split action is pure"
    (let [state (sample-db)
          payload {:block-id "b" :cursor-pos 5}
          result1 (nexus/smart-split state payload)
          result2 (nexus/smart-split state payload)]
      (is (= result1 result2)))))

(deftest escape-edit-is-pure
  (testing "escape-edit action is pure"
    (let [state (sample-db)
          payload {:block-id "b"}
          result1 (nexus/escape-edit state payload)
          result2 (nexus/escape-edit state payload)]
      (is (= result1 result2)))))

;; ── Action Structure Tests ────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-up-returns-valid-effects
  (testing "navigate-up returns list of valid effects"
    (let [state (sample-db)
          result (nexus/navigate-up state {:block-id "b" :cursor-row :first})]
      (is (vector? result) "Should return a vector")
      (is (every? vector? result) "Each effect should be a vector")
      (is (some #(= :effects/dispatch-intent (first %)) result)
          "Should include dispatch-intent effect")
      (is (some #(= :effects/log-devtools (first %)) result)
          "Should include log-devtools effect"))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-up-dispatches-correct-intent
  (testing "navigate-up produces :navigate-with-cursor-memory intent"
    (let [state (sample-db)
          effects (nexus/navigate-up state {:block-id "b" :cursor-row :first})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :navigate-with-cursor-memory (:type intent))
          "Should dispatch navigate-with-cursor-memory intent")
      (is (= "b" (:current-block-id intent)) "Should preserve block-id as current-block-id")
      (is (= :up (:direction intent)) "Should set direction to :up"))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-down-dispatches-correct-intent
  (testing "navigate-down produces :navigate-with-cursor-memory intent"
    (let [state (sample-db)
          effects (nexus/navigate-down state {:block-id "b" :cursor-row :last})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :navigate-with-cursor-memory (:type intent)))
      (is (= "b" (:current-block-id intent)) "Should preserve block-id as current-block-id")
      (is (= :down (:direction intent)) "Should set direction to :down"))))

(deftest ^{:fr/ids #{:fr.selection/extend-boundary}}
  extend-selection-prev-dispatches-correct-intent
  (testing "extend-selection-prev produces :selection intent"
    (let [state (sample-db)
          effects (nexus/extend-selection-prev state {:block-id "b" :direction :backward})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :selection (:type intent)))
      (is (= :extend-prev (:mode intent)) "Should extend selection upward"))))

(deftest ^{:fr/ids #{:fr.selection/extend-boundary}}
  extend-selection-next-dispatches-correct-intent
  (testing "extend-selection-next produces :selection intent"
    (let [state (sample-db)
          effects (nexus/extend-selection-next state {:block-id "b" :direction :forward})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :selection (:type intent)))
      (is (= :extend-next (:mode intent)) "Should extend selection downward"))))

(deftest ^{:fr/ids #{:fr.edit/smart-split}}
  smart-split-dispatches-correct-intent
  (testing "smart-split produces :context-aware-enter intent"
    (let [state (sample-db)
          effects (nexus/smart-split state {:block-id "b" :cursor-pos 5})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :context-aware-enter (:type intent)))
      (is (= "b" (:block-id intent)))
      (is (= 5 (:cursor-pos intent)) "Should preserve cursor position"))))

(deftest escape-edit-dispatches-correct-intent
  (testing "escape-edit produces :exit-edit intent"
    (let [state (sample-db)
          effects (nexus/escape-edit state {:block-id "b"})
          dispatch-effect (first (filter #(= :effects/dispatch-intent (first %)) effects))
          intent (second dispatch-effect)]
      (is (= :exit-edit (:type intent))))))

;; ── Effect Tests ──────────────────────────────────────────────────────────────
;; NOTE: dispatch-intent effect requires browser context (session atom).
;; These tests verify DB-only behavior via api/dispatch directly.

(deftest dispatch-intent-applies-db-operations
  (testing "api/dispatch applies DB operations for valid intents"
    (let [db (sample-db)
          session {}
          intent {:type :update-content :block-id "a" :text "modified"}
          result (api/dispatch db session intent)
          new-db (:db result)]
      ;; update-content should change the block text
      (is (= "modified" (get-in new-db [:nodes "a" :props :text]))
          "Should apply intent operations to DB"))))

(deftest dispatch-handles-invalid-intents-gracefully
  (testing "api/dispatch handles invalid intents gracefully"
    (let [db (sample-db)
          session {}
          invalid-intent {:type :nonexistent-intent-type}
          result (api/dispatch db session invalid-intent)
          new-db (:db result)]
      ;; Invalid intent should not crash or mutate DB unexpectedly
      (is (map? new-db) "DB should still be a valid map")
      (is (contains? new-db :nodes) "DB structure should be preserved"))))

;; ── Single Dispatch Guarantee Tests ──────────────────────────────────────────

(deftest actions-return-exactly-two-effects
  (testing "Each action returns exactly 2 effects (dispatch + log)"
    (let [state (sample-db)]
      (is (= 2 (count (nexus/navigate-up state {:block-id "a" :cursor-row :first}))))
      (is (= 2 (count (nexus/navigate-down state {:block-id "a" :cursor-row :last}))))
      (is (= 2 (count (nexus/extend-selection-prev state {:block-id "a" :direction :backward}))))
      (is (= 2 (count (nexus/extend-selection-next state {:block-id "a" :direction :forward}))))
      (is (= 2 (count (nexus/smart-split state {:block-id "a" :cursor-pos 0}))))
      (is (= 2 (count (nexus/escape-edit state {:block-id "a"})))))))

(deftest actions-dispatch-exactly-one-intent
  (testing "Each action dispatches exactly one kernel intent (no double-dispatch)"
    (let [state (sample-db)
          count-intents (fn [effects]
                          (count (filter #(= :effects/dispatch-intent (first %)) effects)))]
      (is (= 1 (count-intents (nexus/navigate-up state {:block-id "a" :cursor-row :first})))
          "navigate-up should dispatch exactly 1 intent")
      (is (= 1 (count-intents (nexus/navigate-down state {:block-id "a" :cursor-row :last})))
          "navigate-down should dispatch exactly 1 intent")
      (is (= 1 (count-intents (nexus/extend-selection-prev state {:block-id "a" :direction :backward})))
          "extend-selection-prev should dispatch exactly 1 intent")
      (is (= 1 (count-intents (nexus/extend-selection-next state {:block-id "a" :direction :forward})))
          "extend-selection-next should dispatch exactly 1 intent")
      (is (= 1 (count-intents (nexus/smart-split state {:block-id "a" :cursor-pos 0})))
          "smart-split should dispatch exactly 1 intent")
      (is (= 1 (count-intents (nexus/escape-edit state {:block-id "a"})))
          "escape-edit should dispatch exactly 1 intent"))))

;; ── Regression Tests ──────────────────────────────────────────────────────────

(deftest navigate-actions-preserve-block-id
  (testing "Navigation actions preserve block-id as :current-block-id in intent"
    (let [state (sample-db)]
      (doseq [block-id ["a" "b" "c"]]
        (let [up-effects (nexus/navigate-up state {:block-id block-id :cursor-row :first})
              up-intent (second (first (filter #(= :effects/dispatch-intent (first %)) up-effects)))
              down-effects (nexus/navigate-down state {:block-id block-id :cursor-row :last})
              down-intent (second (first (filter #(= :effects/dispatch-intent (first %)) down-effects)))]
          (is (= block-id (:current-block-id up-intent))
              (str "navigate-up should preserve block-id as :current-block-id: " block-id))
          (is (= block-id (:current-block-id down-intent))
              (str "navigate-down should preserve block-id as :current-block-id: " block-id)))))))

(deftest selection-actions-include-direction
  (testing "Selection actions include direction metadata for tracking"
    (let [state (sample-db)
          prev-effects (nexus/extend-selection-prev state {:block-id "a" :direction :backward})
          prev-log (second (first (filter #(= :effects/log-devtools (first %)) prev-effects)))
          next-effects (nexus/extend-selection-next state {:block-id "a" :direction :forward})
          next-log (second (first (filter #(= :effects/log-devtools (first %)) next-effects)))]
      (is (= :backward (:direction prev-log))
          "extend-selection-prev should log :backward direction")
      (is (= :forward (:direction next-log))
          "extend-selection-next should log :forward direction"))))
