(ns kernel.schema
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
  "Placement anchor specification - relational positioning only"
  [:or
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

(def Ref
  "Reference to another node with kind and optional anchor.
   
   Can be either:
   - Simple string ID (shorthand for link ref)
   - Full map with :target, :kind, and optional :anchor
   
   Examples:
   - \"node-123\"                                    ; link to node-123
   - {:target \"node-123\" :kind :link}             ; explicit link
   - {:target \"node-123\" :kind :selection}        ; selection
   - {:target \"node-123\" :kind :highlight         ; highlight with anchor
      :anchor {:path [:props :text] :range [10 24]}}"
  [:or
   :string
   [:map
    [:target Id]
    [:kind [:enum :link :selection :highlight]]
    [:anchor {:optional true} :map]]])

(def Derived
  "Derived data structure"
  [:map
   [:parent-of [:map-of Id [:or Id :keyword]]]
   [:index-of [:map-of Id :int]]
   [:prev-id-of [:map-of Id [:maybe Id]]]
   [:next-id-of [:map-of Id [:maybe Id]]]
   [:pre :map]
   [:post :map]
   [:id-by-pre :map]
   [:doc/pre :map]
   [:doc/id-by-pre :map]])

(def Db
  "Canonical database structure"
  [:map
   [:nodes [:map-of Id Node]]
   [:children-by-parent [:map-of Parent [:vector Id]]]
   [:roots [:vector :keyword]]
   [:derived Derived]])

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

(def Op-Delete
  "Delete node operation - permanently removes node from database"
  [:map
   [:op [:= :delete-node]]
   [:id Id]])

(def Op
  "Any valid operation"
  [:or Op-Create Op-Place Op-Update Op-Delete])

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