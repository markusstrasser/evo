(ns core.schema
  "Malli schemas for the three-op kernel contracts."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.generator :as mg]
            [malli.error :as me]
            #?(:clj [malli.instrument :as mi])))

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
   [:id-by-pre :map]])

(def Db
  "Canonical database structure"
  [:map
   [:nodes [:map-of Id Node]]
   [:children-by-parent [:map-of Parent [:vector Id]]]
   [:roots [:set :keyword]]
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
  (str "id-" #?(:clj (System/currentTimeMillis)
                :cljs (.now js/Date)) "-" (rand-int 1000)))

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

(def api-transformer
  "Composable transformer for API input/output"
  (mt/transformer
   mt/string-transformer
   mt/strip-extra-keys-transformer
   mt/default-value-transformer))

(def strict-transformer
  "Strict transformer that validates exact shape"
  (mt/transformer
   mt/strip-extra-keys-transformer))

(defn decode-op
  "Decode operation data with schema-driven transformation"
  [op-data]
  (m/decode Op op-data api-transformer))

(defn decode-transaction
  "Decode transaction data with schema-driven transformation"
  [tx-data]
  (m/decode Transaction tx-data api-transformer))

(defn encode-db
  "Encode database for serialization"
  [db]
  (m/encode Db db api-transformer))

(defn decode-db
  "Decode database from serialization"
  [db-data]
  (m/decode Db db-data api-transformer))

(def Op-Create-Schema
  "Function schema for create-node with guards"
  [:=>
   [:cat Db Id :keyword :map]
   Db
   [:fn {:error/message "Output must be valid Db"}
    '(fn [{:keys [ret]}]
       (malli.core/validate Db ret))]])

(def Op-Place-Schema
  "Function schema for place with guards"
  [:=>
   [:cat Db Id Parent At]
   Db
   [:fn {:error/message "Output must be valid Db"}
    '(fn [{:keys [ret]}]
       (malli.core/validate Db ret))]])

(def Op-Update-Schema
  "Function schema for update-node with guards"
  [:=>
   [:cat Db Id :map]
   Db
   [:fn {:error/message "Output must be valid Db"}
    '(fn [{:keys [ret]}]
       (malli.core/validate Db ret))]])

(defn generate-op
  "Generate random operation for testing"
  ([]
   (mg/generate Op))
  ([schema]
   (mg/generate schema)))

(defn generate-transaction
  "Generate random transaction for testing"
  ([]
   (mg/generate Transaction))
  ([size]
   (mg/generate [:vector {:min size :max size} Op])))

(defn generate-db
  "Generate random database for testing"
  []
  (mg/generate Db))

(defn generate-create-op
  "Generate random create operation"
  []
  (mg/generate Op-Create))

(defn generate-place-op
  "Generate random place operation"
  []
  (mg/generate Op-Place))

(defn generate-update-op
  "Generate random update operation"
  []
  (mg/generate Op-Update))

(defn infer-node-schema
  "Infer schema from node data samples"
  [nodes]
  (when (seq nodes)
    [:map
     [:type :keyword]
     [:props :map]]))

(defn infer-op-schema
  "Infer operation schema from data"
  [op]
  (case (:op op)
    :create-node Op-Create
    :place Op-Place
    :update-node Op-Update
    :any))

(def fast-op-validator
  "Pre-compiled fast validator for operations"
  (m/validator Op))

(def fast-db-validator
  "Pre-compiled fast validator for database"
  (m/validator Db))

(def fast-transaction-validator
  "Pre-compiled fast validator for transactions"
  (m/validator Transaction))

(defn validate-op-fast
  "Fast operation validation using pre-compiled validator"
  [op]
  (fast-op-validator op))

(defn validate-db-fast
  "Fast database validation using pre-compiled validator"
  [db]
  (fast-db-validator db))

(defn validate-transaction-fast
  "Fast transaction validation using pre-compiled validator"
  [tx]
  (fast-transaction-validator tx))

(defn explain-human
  "Human-readable schema explanation"
  [schema value]
  (-> (m/explain schema value)
      (me/humanize)))

#?(:clj
   (defn instrument-ops!
     "Instrument operation functions with schema validation"
     [ns-sym]
     (mi/collect! {:ns ns-sym})
     (mi/instrument!)))