(ns evolver.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [evolver.constants :as constants]))

;; Core runtime schemas for evolver

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
   [:selection [:vector string?]]
   [:highlighted {:optional true} [:set string?]]
   [:collapsed {:optional true} [:set string?]]
   [:hovered-referencers {:optional true} [:set string?]]])

;; Derived metadata schema
(def derived-schema
  [:map
   [:depth [:map-of string? [:maybe number?]]]
   [:paths [:map-of string? [:maybe [:vector string?]]]]
   [:parent-of [:map-of string? string?]]])

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
   [:selected-op {:optional true} [:maybe keyword?]]
   [:log-level {:optional true} [:enum :debug :info :warn :error]]
   [:log-history {:optional true} [:sequential [:map
                                                [:level keyword?]
                                                [:message string?]
                                                [:timestamp number?]
                                                [:data {:optional true} any?]]]]])

;; Command schemas for validation
(def node-id-schema string?)
(def parent-id-schema string?)
(def position-schema [:or int? map? nil?])
(def node-type-schema keyword?)
(def node-props-schema [:map-of keyword? any?])

(def node-data-schema
  [:map
   [:type node-type-schema]
   [:props {:optional true} node-props-schema]])

;; Individual command schemas
(def insert-command-schema
  [:map
   [:op [:= :insert]]
   [:parent-id parent-id-schema]
   [:node-id node-id-schema]
   [:node-data node-data-schema]
   [:position {:optional true} position-schema]])

(def delete-command-schema
  [:map
   [:op [:= :delete]]
   [:node-id node-id-schema]
   [:recursive {:optional true} boolean?]])

(def move-command-schema
  [:map
   [:op [:= :move]]
   [:node-id node-id-schema]
   [:new-parent-id parent-id-schema]
   [:position {:optional true} position-schema]])

(def patch-command-schema
  [:map
   [:op [:= :patch]]
   [:node-id node-id-schema]
   [:updates [:map-of keyword? any?]]])

(def reorder-command-schema
  [:map
   [:op [:= :reorder]]
   [:node-id node-id-schema]
   [:parent-id parent-id-schema]
   [:to-index int?]])

(def add-reference-command-schema
  [:map
   [:op [:= :add-reference]]
   [:from-node-id node-id-schema]
   [:to-node-id node-id-schema]])

(def remove-reference-command-schema
  [:map
   [:op [:= :remove-reference]]
   [:from-node-id node-id-schema]
   [:to-node-id node-id-schema]])

(def undo-command-schema
  [:map [:op [:= :undo]]])

(def redo-command-schema
  [:map [:op [:= :redo]]])

;; Forward declare for transaction schema
(def transaction-command-schema
  [:map
   [:op [:= :transaction]]
   [:commands [:vector any?]]])

;; Combined command schema
(def command-schema
  [:or
   insert-command-schema
   delete-command-schema
   move-command-schema
   patch-command-schema
   reorder-command-schema
   add-reference-command-schema
   remove-reference-command-schema
   undo-command-schema
   redo-command-schema
   transaction-command-schema])

;; UI command schemas
(def ui-command-name-schema keyword?)
(def ui-command-params-schema map?)
(def ui-command-schema
  [:tuple ui-command-name-schema ui-command-params-schema])

;; Runtime validation functions
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

(defn validate-transaction
  "Validate a transaction log entry"
  [tx]
  (let [tx-schema [:map
                   [:op keyword?]
                   [:timestamp number?]
                   [:args [:map-of keyword? any?]]]
        result (m/validate tx-schema tx)]
    (when-not result
      (let [errors (me/humanize (m/explain tx-schema tx))]
        (throw (ex-info "Invalid transaction"
                        {:errors errors :transaction tx}))))
    tx))

;; Schema registry for runtime validation
(def registry
  (merge
   (m/default-schemas)
   {:node node-schema
    :children-by-parent children-by-parent-schema
    :view view-schema
    :derived derived-schema
    :db db-schema
    :command command-schema
    :ui-command ui-command-schema}))