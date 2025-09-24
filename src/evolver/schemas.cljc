(ns evolver.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [evolver.constants :as constants]))

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

;; Forward declaration for command-schema (defined later)
(declare command-schema)

;; Main database schema
(def db-schema
  [:map
   [:nodes [:map-of string? node-schema]]
   [:children-by-parent children-by-parent-schema]
   [:view view-schema]
   [:derived derived-schema]
   [:references {:optional true} references-schema]
   [:history {:optional true} [:vector any?]] ; Changed from command-schema to any? to avoid forward declaration issues
   [:history-index {:optional true} int?]
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

(defn validate-db-state
  "Alias for validate-db for backward compatibility"
  [db]
  (validate-db db))

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

;; Log levels moved to constants.cljc

;; Operation types moved to constants.cljc

;; Command schemas
(def command-op-schema
  "Schema for command operation type"
  keyword?)

(def node-id-schema
  "Schema for node identifier"
  string?)

(def parent-id-schema
  "Schema for parent node identifier"
  string?)

(def position-schema
  "Schema for position specification in commands"
  [:or int? map? nil?])

(def node-type-schema
  "Schema for node type"
  keyword?)

(def node-text-schema
  "Schema for node text content"
  string?)

(def node-props-schema
  "Schema for node properties"
  [:map-of keyword? any?])

(def node-data-schema
  "Schema for node data structure"
  [:map
   [:type node-type-schema]
   [:props {:optional true} node-props-schema]])

;; Individual command schemas
(def insert-command-schema
  "Schema for insert command"
  [:map
   [:op [:= :insert]]
   [:parent-id parent-id-schema]
   [:node-id node-id-schema]
   [:node-data node-data-schema]
   [:position {:optional true} position-schema]])

(def delete-command-schema
  "Schema for delete command"
  [:map
   [:op [:= :delete]]
   [:node-id node-id-schema]
   [:recursive {:optional true} boolean?]])

(def move-command-schema
  "Schema for move command"
  [:map
   [:op [:= :move]]
   [:node-id node-id-schema]
   [:new-parent-id parent-id-schema]
   [:position {:optional true} position-schema]])

(def patch-command-schema
  "Schema for patch command"
  [:map
   [:op [:= :patch]]
   [:node-id node-id-schema]
   [:updates [:map-of keyword? any?]]])

(def reorder-command-schema
  "Schema for reorder command"
  [:map
   [:op [:= :reorder]]
   [:node-id node-id-schema]
   [:parent-id parent-id-schema]
   [:from-index int?]
   [:to-index int?]])

(def add-reference-command-schema
  "Schema for add-reference command"
  [:map
   [:op [:= :add-reference]]
   [:from-node-id node-id-schema]
   [:to-node-id node-id-schema]])

(def remove-reference-command-schema
  "Schema for remove-reference command"
  [:map
   [:op [:= :remove-reference]]
   [:from-node-id node-id-schema]
   [:to-node-id node-id-schema]])

(def undo-command-schema
  "Schema for undo command"
  [:map
   [:op [:= :undo]]])

(def redo-command-schema
  "Schema for redo command"
  [:map
   [:op [:= :redo]]])

;; Combined command schema
(def command-schema
  "Schema for all valid commands"
  [:or
   insert-command-schema
   delete-command-schema
   move-command-schema
   patch-command-schema
   reorder-command-schema
   add-reference-command-schema
   remove-reference-command-schema
   undo-command-schema
   redo-command-schema])

;; UI command schemas (for the command registry)
(def ui-command-name-schema
  "Schema for UI command name"
  keyword?)

(def ui-command-params-schema
  "Schema for UI command parameters"
  map?)

(def ui-command-schema
  "Schema for UI command tuple"
  [:tuple ui-command-name-schema ui-command-params-schema])

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

;; Command validation functions
(defn validate-command
  "Validate a command against its schema"
  [command]
  (let [result (m/validate command-schema command)]
    (when-not result
      (let [errors (me/humanize (m/explain command-schema command))]
        (throw (ex-info "Invalid command"
                        {:errors errors :command command}))))
    command))

(defn validate-ui-command
  "Validate a UI command against its schema"
  [ui-command]
  (let [result (m/validate ui-command-schema ui-command)]
    (when-not result
      (let [errors (me/humanize (m/explain ui-command-schema ui-command))]
        (throw (ex-info "Invalid UI command"
                        {:errors errors :ui-command ui-command}))))
    ui-command))

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
    :operation-result operation-result-schema
    :command command-schema
    :ui-command ui-command-schema}))