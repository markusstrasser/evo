(ns kernel.errors
  "Structured error types using exoscale/ex hierarchy.

   All errors derive from ::kernel-error for easy top-level catching.
   Each error category has semantic meaning for AI agents to understand."
  (:require [exoscale.ex :as ex]))

;; ── Error Hierarchy ───────────────────────────────────────────────────────────

;; Root error type
(ex/derive ::validation-error   ::kernel-error)
(ex/derive ::transaction-error  ::kernel-error)
(ex/derive ::intent-error       ::kernel-error)
(ex/derive ::query-error        ::kernel-error)

;; Validation errors
(ex/derive ::invalid-intent     ::validation-error)
(ex/derive ::invalid-operation  ::validation-error)
(ex/derive ::schema-violation   ::validation-error)

;; Transaction errors
(ex/derive ::node-not-found     ::transaction-error)
(ex/derive ::invalid-placement  ::transaction-error)
(ex/derive ::circular-reference ::transaction-error)

;; Intent errors
(ex/derive ::no-handler         ::intent-error)
(ex/derive ::handler-failed     ::intent-error)

;; Query errors
(ex/derive ::invalid-query      ::query-error)
(ex/derive ::node-missing       ::query-error)

;; ── Error Constructors ────────────────────────────────────────────────────────

(defn ex-invalid-intent
  "Throw when intent doesn't match its spec."
  [intent spec errors]
  (ex/ex-info ::invalid-intent
    (str "Intent validation failed: " (:type intent))
    {:intent intent
     :spec spec
     :errors errors}))

(defn ex-node-not-found
  "Throw when operation references non-existent node."
  [node-id operation]
  (ex/ex-info ::node-not-found
    (str "Node not found: " node-id)
    {:node-id node-id
     :operation operation
     :available-nodes :elided}))  ;; Add in handler

(defn ex-invalid-placement
  "Throw when placement violates tree constraints."
  [node-id parent-id reason]
  (ex/ex-info ::invalid-placement
    (str "Invalid placement: " reason)
    {:node-id node-id
     :parent-id parent-id
     :reason reason}))

(defn ex-circular-reference
  "Throw when placement would create cycle."
  [node-id ancestor-ids]
  (ex/ex-info ::circular-reference
    "Placement would create circular reference"
    {:node-id node-id
     :ancestor-ids ancestor-ids}))

(defn ex-no-handler
  "Throw when no handler registered for intent type."
  [intent-type]
  (ex/ex-info ::no-handler
    (str "No handler registered for intent type: " intent-type)
    {:intent-type intent-type}))

(defn ex-handler-failed
  "Throw when intent handler throws an error."
  [intent cause]
  (ex/ex-info ::handler-failed
    (str "Intent handler failed: " (:type intent))
    {:intent intent
     :cause cause}))
