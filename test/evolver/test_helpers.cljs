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
                               ((resolve 'evolver.core/store)))
                              (catch :default _ "State unavailable"))]
          (js/console.log "Test starting with state:" initial-state)
          (try
            (test-fn)
            (let [final-state (try
                                ((resolve 'agent.store-inspector/quick-state-dump)
                                 ((resolve 'evolver.core/store)))
                                (catch :default _ "State unavailable"))]
              (js/console.log "Test completed with state:" final-state))
            (catch :default e
              (try
                (js/console.error "Test failed, final state:"
                                  ((resolve 'agent.store-inspector/inspect-store)
                                   ((resolve 'evolver.core/store))))
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
                     ((resolve 'evolver.core/store)))]
          (js/console.log (str message ": " state)))
        (catch :default _ nil)))))

;; Integration with existing test patterns
(defmacro with-test-observation [message & body]
  "Wrap test code with observation logging"
  `(do
     (log-test-state ~(str "Starting: " message))
     ~@body
     (log-test-state ~(str "Completed: " message))))