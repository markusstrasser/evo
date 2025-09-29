(ns core.schema
  "Malli schemas for the kernel API.
   
   Provides machine-checkable contracts for adapters and LLMs."
  (:require [malli.core :as m]
            [malli.util :as mu]))

(def Id
  "Node identifier type."
  :string)

(def Parent
  "Parent can be either a node id or a keyword sentinel."
  [:or Id keyword?])

(def At
  "Position specification for placement operations."
  [:or int?
   [:= :first]
   [:= :last]
   [:map [:before Id]]
   [:map [:after Id]]])

(def Node
  "Node shape with type and properties."
  [:map
   [:type keyword?]
   [:props :any]])

(def Db
  "Canonical database structure."
  [:map
   [:nodes [:map-of Id Node]]
   [:children-by-parent [:map-of Parent [:vector Id]]]
   [:derived :any]])

(def Op-Create
  "Create node operation schema."
  [:map
   [:op [:= :create-node]]
   [:id Id]
   [:type keyword?]
   [:props :any]
   [:under Parent]
   [:at At]])

(def Op-Place
  "Place/move node operation schema."
  [:map
   [:op [:= :place]]
   [:id Id]
   [:under Parent]
   [:at At]])

(def Op-Update
  "Update node properties operation schema."
  [:map
   [:op [:= :update-node]]
   [:id Id]
   [:props :any]])

(def Op
  "Union of all operation types."
  [:or Op-Create Op-Place Op-Update])

(def Tx
  "Transaction - vector of operations."
  [:vector Op])

(defn describe-ops
  "Returns schema descriptions for the kernel API.
   
   Provides machine-readable contracts for all kernel operations."
  []
  {:Db Db
   :Op Op
   :Tx Tx
   :Create Op-Create
   :Place Op-Place
   :Update Op-Update
   :Id Id
   :Parent Parent
   :At At})