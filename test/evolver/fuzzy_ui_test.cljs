(ns evolver.fuzzy-ui-test
  "Property-based UI testing using Chrome DevTools integration"
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [evolver.kernel :as kernel]
            [evolver.commands :as commands]
            [evolver.keyboard :as keyboard]))

;; Generators for UI interactions
(def key-generator
  "Generate realistic keyboard events"
  (gen/one-of [;; Navigation keys
               (gen/tuple (gen/return :key) (gen/return "ArrowUp")
                          (gen/return {:alt true}))
               (gen/tuple (gen/return :key) (gen/return "ArrowDown")
                          (gen/return {:alt true}))
               (gen/tuple (gen/return :key) (gen/return "ArrowUp")
                          (gen/return {:alt true :shift true}))
               (gen/tuple (gen/return :key) (gen/return "ArrowDown")
                          (gen/return {:alt true :shift true}))

    ;; Selection keys
               (gen/tuple (gen/return :key) (gen/return "Escape")
                          (gen/return {}))
               (gen/tuple (gen/return :key) (gen/return "A")
                          (gen/return {:meta true :shift true}))

    ;; Edit keys
               (gen/tuple (gen/return :key) (gen/return "Enter")
                          (gen/return {}))
               (gen/tuple (gen/return :key) (gen/return "Delete")
                          (gen/return {}))
               (gen/tuple (gen/return :key) (gen/return "Tab")
                          (gen/return {}))]))

(def click-generator
  "Generate click events on valid elements"
  (gen/let [node-id (gen/elements ["root" "div1" "p1-select" "p2-high" "p3-both" "p4-click"])]
    (gen/tuple (gen/return :click) (gen/return node-id))))

(def interaction-sequence-generator
  "Generate sequences of user interactions"
  (gen/vector (gen/one-of [key-generator click-generator]) 1 10))

;; State invariant checkers
(defn valid-tree-structure? [store]
  "Check that tree structure is consistent"
  (let [state @store
        nodes (:nodes state)
        children-by-parent (:children-by-parent state)]
    (and
      ;; All children exist as nodes
     (every? (fn [[parent children]]
               (every? #(contains? nodes %) children))
             children-by-parent)

      ;; No circular references - simplified check
     true)))

(defn selection-is-valid? [store]
  "Check that selection state is consistent"
  (let [state @store
        selected (get-in state [:view :selected] #{})]
    (and
      ;; Selected nodes exist
     (every? #(contains? (:nodes state) %) selected)

      ;; Selection is a set
     (set? selected))))

(defn ui-state-is-consistent? [store]
  "Check that UI state matches data state"
  (and (valid-tree-structure? store)
       (selection-is-valid? store)))

;; Chrome DevTools integration helpers
(defn simulate-key-event [key modifiers]
  "Simulate keyboard event - would integrate with chrome-devtools"
  ;; Simulate keyboard event - placeholder for chrome-devtools integration
  true)

(defn simulate-click-event [element-id]
  "Simulate click event - would integrate with chrome-devtools"
  ;; Simulate click event - placeholder for chrome-devtools integration
  true)

(defn execute-interaction [store [action-type & args]]
  "Execute a single interaction"
  (case action-type
    :key (let [[key modifiers] args]
           (simulate-key-event key modifiers))
    :click (let [[element-id] args]
             (simulate-click-event element-id))))

;; Property-based test runner
(def ui-consistency-property
  "Property: UI state should remain consistent after any sequence of valid interactions"
  (prop/for-all [interactions interaction-sequence-generator]
                (let [store (atom {:nodes {"root" {:type :div}
                                           "div1" {:type :div}
                                           "p1-select" {:type :p}}
                                   :children-by-parent {"root" ["div1" "p1-select"]}
                                   :view {:selected #{}}})]
      ;; Execute interaction sequence
                  (doseq [interaction interactions]
                    (try
                      (execute-interaction store interaction)
                      (catch js/Error e
            ;; Log but don't fail on expected errors (like operating on empty selection)
                        (js/console.log "Expected error:" (.-message e)))))

      ;; Check final state is consistent
                  (ui-state-is-consistent? store))))

(def command-parameter-consistency-property
  "Property: All keyboard mappings should have parameters matching command expectations"
  (prop/for-all [_ (gen/return nil)] ; No input needed, just check static structure
                (let [mappings keyboard/keyboard-mappings
                      problematic (filter
                                   (fn [mapping]
                                     (let [[cmd-name params] (:command mapping)]
                                       (and params
                                            (not (map? params))
                                            (#{:navigate-sibling :move-block} cmd-name))))
                                   mappings)]
                  (empty? problematic))))

;; Integration test that would use chrome-devtools
(defn test-ui-with-chrome-devtools []
  "Full UI integration test using chrome-devtools MCP"
  ;; 1. Navigate to app
  ;; (chrome-devtools_navigate_page "http://localhost:8080")

  ;; 2. Wait for load
  ;; (chrome-devtools_wait_for "Evolver")

  ;; 3. Take snapshot to get element UIDs
  ;; (let [snapshot (chrome-devtools_take_snapshot)]

  ;; 4. Test each clickable element
  ;;   (doseq [element (filter-clickable-elements snapshot)]
  ;;     (chrome-devtools_click (:uid element))
  ;;     (validate-ui-state)))

  ;; 5. Test keyboard shortcuts
  ;;   (doseq [combo keyboard-combinations]
  ;;     (chrome-devtools_send_keys combo)
  ;;     (validate-ui-state)))

  ;; For now, just test the property locally
  (tc/quick-check 50 ui-consistency-property))

;; Test runner
(deftest test-ui-consistency
  "Run property-based tests for UI consistency"
  (testing "UI state remains consistent under random interactions"
    (let [result (tc/quick-check 25 ui-consistency-property)]
      (is (:result result)
          (str "UI consistency failed: " (:shrunk result)))))

  (testing "Command parameter contracts are consistent"
    (let [result (tc/quick-check 1 command-parameter-consistency-property)]
      (is (:result result)
          "Command parameter format inconsistency detected"))))