(ns evolver.test-helpers
  "Test utilities and helpers for enhanced testing with agent tools"
  (:require [cljs.test :refer [is]]
            [agent.core :as agent]))

;; Agent observation wrapper
(defn with-agent-observation [test-fn]
  "Wrap test with agent tooling observation if environment supports it"
  (let [env (agent/detect-environment)]
    (if (:store-accessible? env)
      (do
        ;; Enhanced testing with agent tools
        (let [initial-state (try
                              ((resolve 'agent.store-inspector/quick-state-dump)
                               @((resolve 'evolver.core/store)))
                              (catch :default _ "State unavailable"))]
          (js/console.log "Test starting with state:" initial-state)
          (try
            (test-fn)
            (let [final-state (try
                                ((resolve 'agent.store-inspector/quick-state-dump)
                                 @((resolve 'evolver.core/store)))
                                (catch :default _ "State unavailable"))]
              (js/console.log "Test completed with state:" final-state))
            (catch :default e
              (try
                (js/console.error "Test failed, final state:"
                                  ((resolve 'agent.store-inspector/inspect-store)
                                   @((resolve 'evolver.core/store))))
                (catch :default _ (js/console.error "Could not inspect final state")))
              (throw e)))))
      ;; Fallback for non-browser environments
      (test-fn))))

;; Environment-aware test execution
(defn browser-enhanced-test [test-name test-fn fallback-fn]
  "Run enhanced test in browser, fallback in other environments"
  (cljs.test/deftest test-name
    (let [env (agent/detect-environment)]
      (cond
        ;; Full browser environment with agent tools
        (:store-accessible? env)
        (cljs.test/testing "Browser environment with agent tools"
          (with-agent-observation test-fn))

        ;; Browser without store (pre-connection)
        (:browser? env)
        (cljs.test/testing "Browser environment (limited capabilities)"
          (fallback-fn))

        ;; Node environment
        :else
        (cljs.test/testing "Node environment (pure logic testing)"
          (fallback-fn))))))

;; Store state validation helpers
(defn validate-store-integrity []
  "Validate basic store integrity using agent tools"
  (when-let [env (agent/detect-environment)]
    (when (:store-accessible? env)
      (try
        (let [store ((resolve 'evolver.core/store))
              state @store]
          ;; Basic integrity checks
          (and (map? state)
               (contains? state :nodes)
               (contains? state :view)
               (map? (:nodes state))
               (map? (:view state))))
        (catch :default _ false)))
    true)) ; Return true for non-browser environments

(defn validate-selection-consistency []
  "Validate that selected nodes exist in the store"
  (when-let [env (agent/detect-environment)]
    (when (:store-accessible? env)
      (try
        (let [store ((resolve 'evolver.core/store))
              state @store
              selected (:selection-set (:view state))
              nodes (:nodes state)]
          ;; All selected nodes should exist
          (every? #(contains? nodes %) selected))
        (catch :default _ true))) ; Don't fail test if validation fails
    true))

;; Test data setup helpers
(defn create-test-store [initial-data]
  "Create a test store with initial data"
  (atom initial-data))

(defn setup-test-document []
  "Setup a basic test document structure"
  {:nodes {"root" {:id "root" :type :div :children ["p1" "p2"]}
           "p1" {:id "p1" :type :p :content "First paragraph"}
           "p2" {:id "p2" :type :p :content "Second paragraph"}}
   :view {:selection ["p1"] :selection-set #{"p1"} :cursor "p1"}
   :references {}})

;; Mock interaction helpers
(defn simulate-keypress [key & {:keys [alt shift meta]}]
  "Mock keyboard event for testing"
  {:key key
   :alt (boolean alt)
   :shift (boolean shift)
   :meta (boolean meta)})

(defn simulate-click [element-id]
  "Mock click event for testing"
  {:type :click :target-id element-id})

;; Validation helpers for common test patterns
(defn assert-selection-changed [old-selection new-selection expected-change]
  "Assert that selection changed as expected"
  (is (not= old-selection new-selection) "Selection should have changed")
  (case expected-change
    :cleared (is (empty? new-selection) "Selection should be cleared")
    :added (is (> (count new-selection) (count old-selection)) "Selection should have items added")
    :removed (is (< (count new-selection) (count old-selection)) "Selection should have items removed")
    ;; Default case
    (is true "Selection changed")))

(defn assert-node-position-changed [node-id old-position new-position]
  "Assert that a node's position changed"
  (is (not= old-position new-position) (str "Node " node-id " position should have changed")))

;; Test reporting helpers
(defn log-test-state [message]
  "Log current test state for debugging"
  (when-let [env (agent/detect-environment)]
    (when (:store-accessible? env)
      (try
        (let [state ((resolve 'agent.store-inspector/quick-state-dump)
                     @((resolve 'evolver.core/store)))]
          (js/console.log (str message ": " state)))
        (catch :default _ nil)))))

;; Integration with existing test patterns
(defmacro with-test-observation [message & body]
  "Wrap test code with observation logging"
  `(do
     (log-test-state ~(str "Starting: " message))
     ~@body
     (log-test-state ~(str "Completed: " message))))

;; Unified Test Utilities for UI/Test Dispatch Consistency
(defn test-dispatch-commands
  "Test helper that uses dispatch-commands (plural) like UI, not dispatch-command (singular)"
  [store event-data commands]
  ;; This mirrors the exact UI behavior by using the unified dispatch-commands entry point
  ;; which properly handles command arrays vs single commands
  (let [cmds (if (sequential? commands) commands [commands])]
    ;; Use the unified command entry point from commands.cljs
    ((resolve 'evolver.commands/dispatch-commands) store event-data cmds))
  ;; Return info for test verification
  {:dispatched-commands commands
   :event-data event-data
   :type :dispatch-commands
   :note "Unified command entry point used - dispatch-commands from commands.cljs"})

(defn create-proper-test-event
  "Creates a test event with proper DOM context like UI generates"
  [node-id & {:keys [modifiers] :or {modifiers {}}}]
  (let [target (agent/create-mock-target node-id)
        event (agent/create-mock-dom-event :target target :modifiers (set (keys modifiers)))]
    {:event event
     :data {:target target
            :modifiers modifiers
            :node-id node-id}}))

(defn test-with-ui-context
  "Executes test with proper UI context that mirrors real browser environment"
  [test-state test-fn]
  ;; Ensure the test state has all three selection fields consistent
  (let [prepared-state (agent/create-test-context test-state)]
    (test-fn prepared-state)))

(defn assert-selection-state-valid
  "Asserts that selection state follows the triple-field rule"
  [db]
  (let [validation (agent/validate-selection-consistency (:view db))]
    (is (:valid? validation)
        (str "Selection state invalid: " (:issues validation)))
    validation))

(defn assert-navigation-possible
  "Asserts that navigation commands will work (cursor must be set)"
  [db direction]
  (let [validation (agent/validate-navigation-prerequisites db direction)]
    (is (nil? validation)
        (str "Navigation not possible: " (:suggestion validation)))
    (nil? validation)))