(ns agent.schemas
  (:require [malli.core :as m]
            [malli.error :as me]))

;; Agent-specific schemas for development tooling and analysis

;; Transaction log schema (for agent analysis)
(def transaction-schema
  [:map
   [:op keyword?]
   [:timestamp number?]
   [:args {:optional true} [:map-of keyword? any?]]])

;; Agent function input/output schemas
(def namespace-health-schema
  "Schema for analyze-namespace-health results"
  [:map
   [:namespace string?]
   [:status [:enum :healthy :not-found :error]]
   [:public-fns {:optional true} number?]
   [:dependencies {:optional true} number?]
   [:potential-issues {:optional true} [:map-of keyword? any?]]
   [:error {:optional true} string?]])

(def db-structure-schema
  "Schema for validate-db-structure results"
  [:map
   [:valid? boolean?]
   [:missing-keys [:vector string?]]
   [:extra-keys [:vector string?]]
   [:node-count number?]
   [:tx-count number?]])

(def db-diff-schema
  "Schema for db-diff results"
  [:map
   [:nodes-added number?]
   [:children-changed number?]
   [:view-changed number?]
   [:tx-log-growth number?]
   [:summary string?]])

(def operation-result-schema
  "Schema for check-operation-result results"
  [:map
   [:operation any?]
   [:success? boolean?]
   [:changes db-diff-schema]
   [:status [:enum :success :partial :no-op]]])

;; Constants for validation and analysis
(def required-db-keys
  "Required top-level keys in the database"
  #{:nodes :children-by-parent :view :derived})

;; Validation helpers for agent functions
(defn validate-namespace-health-result
  "Validate result from analyze-namespace-health"
  [result]
  (let [valid? (m/validate namespace-health-schema result)]
    (when-not valid?
      (throw (ex-info "Invalid namespace health result"
                      {:errors (me/humanize (m/explain namespace-health-schema result))
                       :result result})))
    result))

(defn validate-db-structure-result
  "Validate result from validate-db-structure"
  [result]
  (let [valid? (m/validate db-structure-schema result)]
    (when-not valid?
      (throw (ex-info "Invalid db structure result"
                      {:errors (me/humanize (m/explain db-structure-schema result))
                       :result result})))
    result))

(defn validate-db-diff-result
  "Validate result from db-diff"
  [result]
  (let [valid? (m/validate db-diff-schema result)]
    (when-not valid?
      (throw (ex-info "Invalid db diff result"
                      {:errors (me/humanize (m/explain db-diff-schema result))
                       :result result})))
    result))

(defn validate-operation-result
  "Validate result from check-operation-result"
  [result]
  (let [valid? (m/validate operation-result-schema result)]
    (when-not valid?
      (throw (ex-info "Invalid operation result"
                      {:errors (me/humanize (m/explain operation-result-schema result))
                       :result result})))
    result))

(defn validate-transaction
  "Validate a transaction"
  [tx]
  (let [result (m/validate transaction-schema tx)]
    (when-not result
      (let [errors (me/humanize (m/explain transaction-schema tx))]
        (throw (ex-info "Transaction validation failed" {:errors errors :transaction tx}))))
    result))

;; Basic malli wrapper functions for agent.core
(defn validate
  "Validate data against schema"
  [schema data]
  (m/validate schema data))

(defn explain
  "Explain validation errors"
  [schema data]
  (m/explain schema data))

(defn humanize
  "Humanize validation errors"
  [explanation]
  (me/humanize explanation))

;; Command parameter schemas for validation
(def select-node-params
  "Schema for select-node command parameters"
  [:map
   [:node-id string?]])

(def hover-node-params
  "Schema for hover-node command parameters"
  [:map
   [:node-id string?]])

(def navigation-params
  "Schema for navigation command parameters"
  [:map
   [:direction [:enum :up :down]]])

;; Schema registry for agent development tools

;; Schema registry for agent development tools
(def registry
  (merge
   (m/default-schemas)
   {:transaction transaction-schema
    :namespace-health namespace-health-schema
    :db-structure db-structure-schema
    :db-diff db-diff-schema
    :operation-result operation-result-schema
    :select-node-params select-node-params
    :hover-node-params hover-node-params
    :navigation-params navigation-params}))