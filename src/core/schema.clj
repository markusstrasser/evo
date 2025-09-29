(ns core.schema
  "Malli schemas for the three-op kernel contracts."
  (:require [malli.core :as m]))

;; Basic types
(def Id
  "Node identifier - string"
  :string)

(def Parent
  "Parent can be either a node ID or a root keyword"
  [:or Id :keyword])

(def At
  "Placement anchor specification"
  [:or
   :int ; index position
   [:= :first] ; first position
   [:= :last] ; last position
   [:map [:before Id]] ; before specific sibling
   [:map [:after Id]]]) ; after specific sibling

;; Database schemas
(def Node
  "Individual node structure"
  [:map
   [:type :keyword]
   [:props :map]])

(def Db
  "Canonical database structure"
  [:map
   [:nodes [:map-of Id Node]]
   [:children-by-parent [:map-of Parent [:vector Id]]]
   [:roots [:set :keyword]]
   [:derived [:map
              [:parent-of [:map-of Id Parent]]
              [:index-of [:map-of Id :int]]
              [:prev-id-of [:map-of Id [:maybe Id]]]
              [:next-id-of [:map-of Id [:maybe Id]]]
              [:pre [:map-of Id :int]]
              [:post [:map-of Id :int]]
              [:id-by-pre [:map-of :int Id]]]]])

(def Derived
  "Derived data structure"
  [:map
   [:parent-of [:map-of Id Parent]]
   [:index-of [:map-of Id :int]]
   [:prev-id-of [:map-of Id [:maybe Id]]]
   [:next-id-of [:map-of Id [:maybe Id]]]
   [:pre [:map-of Id :int]]
   [:post [:map-of Id :int]]
   [:id-by-pre [:map-of :int Id]]])

;; Operation schemas
(def Op-Create
  "Create node operation - creates shell without placing it"
  [:map
   [:op [:= :create-node]]
   [:id Id]
   [:type :keyword]
   [:props :map]])

(def Op-Place
  "Place operation - removes from any current parent and places at new location"
  [:map
   [:op [:= :place]]
   [:id Id]
   [:under Parent]
   [:at At]])

(def Op-Update
  "Update node operation - merges properties"
  [:map
   [:op [:= :update-node]]
   [:id Id]
   [:props :map]])

(def Op
  "Any valid operation"
  [:or Op-Create Op-Place Op-Update])

(def Transaction
  "Collection of operations"
  [:vector Op])

;; Result schemas
(def Issue
  "Validation or processing issue"
  [:map
   [:issue :keyword]
   [:op Op]
   [:at :int]
   [:hint :string]])

(def InterpretResult
  "Result from interpreting transactions"
  [:map
   [:db Db]
   [:issues [:vector Issue]]
   [:trace [:vector :any]]]) ; trace entries can be various shapes

(def ValidateResult
  "Result from validation"
  [:map
   [:ok? :boolean]
   [:errors [:vector :string]]])

;; Compiled schemas for describe-ops
(def compiled-schemas
  {:Op-Create (m/validator Op-Create)
   :Op-Place (m/validator Op-Place)
   :Op-Update (m/validator Op-Update)
   :Op (m/validator Op)
   :Transaction (m/validator Transaction)
   :Db (m/validator Db)
   :InterpretResult (m/validator InterpretResult)
   :ValidateResult (m/validator ValidateResult)})

(defn gen-id
  "Generate a unique ID string."
  []
  (str "id-" (System/currentTimeMillis) "-" (rand-int 1000)))

(defn describe-ops
  "Return compiled Malli schemas for all operations and core types."
  []
  compiled-schemas)

;; Validation helpers
(defn valid-op?
  "Check if operation conforms to schema."
  [op]
  (m/validate Op op))

(defn valid-db?
  "Check if database conforms to schema."
  [db]
  (m/validate Db db))

(defn explain-op
  "Explain why operation doesn't conform to schema."
  [op]
  (m/explain Op op))

(defn explain-db
  "Explain why database doesn't conform to schema."
  [db]
  (m/explain Db db))