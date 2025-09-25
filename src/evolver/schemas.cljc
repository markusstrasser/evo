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
   [:collapsed {:optional true} [:set string?]]])

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

;; Main database schema
(def db-schema
  [:map
   [:nodes [:map-of string? node-schema]]
   [:children-by-parent children-by-parent-schema]
   [:view view-schema]
   [:derived derived-schema]
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

;; Schema registry for runtime validation
(def registry
  (merge
   (m/default-schemas)
   {:node node-schema
    :children-by-parent children-by-parent-schema
    :view view-schema
    :transaction transaction-schema
    :derived derived-schema
    :db db-schema}))