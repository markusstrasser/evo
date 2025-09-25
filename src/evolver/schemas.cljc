(ns evolver.schemas
  (:require [malli.core :as m]
            [malli.error :as me]))

;; Node schema
(def node-schema
  [:map
   [:type keyword?]
   [:props {:optional true} [:map-of keyword? any?]]])

;; Children by parent schema
(def children-by-parent-schema
  [:map-of string? [:vector string?]])

;; View schema
(def view-schema
  [:map
   [:selected [:set string?]]
   [:highlighted {:optional true} [:set string?]]
   [:collapsed {:optional true} [:set string?]]
   [:hovered-referencers {:optional true} [:set string?]]])

;; Transaction log schema
(def transaction-schema
  [:map
   [:op keyword?]
   [:timestamp number?]
   [:args {:optional true} [:map-of keyword? any?]]])

;; Derived metadata schema
(def derived-schema
  [:map
   [:depth [:map-of string? number?]]
   [:paths [:map-of string? [:vector string?]]]])

;; References schema - tracks which nodes reference which other nodes
(def references-schema
  [:map-of string? [:set string?]]) ; node-id -> set of nodes that reference it

;; Main database schema
(def db-schema
  [:map
   [:nodes [:map-of string? node-schema]]
   [:children-by-parent children-by-parent-schema]
   [:view view-schema]
   [:derived derived-schema]
   [:references {:optional true} references-schema]
   [:tx-log {:optional true} [:vector transaction-schema]]
   [:undo-stack {:optional true} [:vector transaction-schema]]
   [:selected-op {:optional true} [:maybe keyword?]]
   [:log-level {:optional true} [:enum :debug :info :warn :error]]
    [:log-history {:optional true} [:sequential [:map
                                                      [:level keyword?]
                                                      [:message string?]
                                                      [:timestamp number?]
                                                      [:data {:optional true} any?]]]]])

;; Validation functions
(defn validate-db
  "Validate database structure against schema"
  [db]
  (let [result (m/validate db-schema db)]
    (when-not result
      (let [errors (me/humanize (m/explain db-schema db))]
        (throw (ex-info "Database validation failed" {:errors errors :db db}))))
    result))

(defn validate-node
  "Validate a single node"
  [node]
  (let [result (m/validate node-schema node)]
    (when-not result
      (let [errors (me/humanize (m/explain node-schema node))]
        (throw (ex-info "Node validation failed" {:errors errors :node node}))))
    result))

(defn validate-transaction
  "Validate a transaction"
  [tx]
  (let [result (m/validate transaction-schema tx)]
    (when-not result
      (let [errors (me/humanize (m/explain transaction-schema tx))]
        (throw (ex-info "Transaction validation failed" {:errors errors :transaction tx}))))
    result))

;; Constants for validation and analysis
(def required-db-keys
  "Required top-level keys in the database"
  #{:nodes :children-by-parent :view :derived :tx-log})

(def log-levels
  "Available log levels in priority order"
  {:debug 0 :info 1 :warn 2 :error 3})

(def operation-types
  "Supported operation types"
  #{:insert :move :patch :delete :reorder :undo :redo})

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

;; Schema registry for runtime validation
(def registry
  (merge
   (m/default-schemas)
   {:node node-schema
    :children-by-parent children-by-parent-schema
    :view view-schema
    :transaction transaction-schema
    :derived derived-schema
    :db db-schema
    :namespace-health namespace-health-schema
    :db-structure db-structure-schema
    :db-diff db-diff-schema
    :operation-result operation-result-schema}))