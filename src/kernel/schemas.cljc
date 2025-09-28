(ns kernel.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            #?(:clj [malli.dev :as md])))

;; --- Registry ------------------------------------------------

(def registry
  {::id [:string {:min 1}]
   ::pos [:or nil?
          [:enum :first :last]
          [:tuple [:= :index] int?]
          [:tuple [:= :before] ::id]
          [:tuple [:= :after] ::id]]

   ;; Core ops (closed)
   ::create-node-op [:map
                     [:op [:= :create-node]]
                     [:id ::id]
                     [:type {:optional true} keyword?]
                     [:props {:optional true} map?]]
   ::place-op [:map
               [:op [:= :place]]
               [:id ::id]
               [:parent-id {:optional true} [:or nil? ::id]]
               [:pos {:optional true} ::pos]]
   ::update-node-op [:map
                     [:op [:= :update-node]]
                     [:id ::id]
                     [:props {:optional true} map?]
                     [:sys {:optional true} map?]
                     [:updates {:optional true} map?]]
   ::prune-op [:map
               [:op [:= :prune]]
               [:pred fn?]]

   ;; Edges primitive ops
   ::edges [:map-of keyword? [:map-of ::id [:set ::id]]]
   ::add-ref-op [:map
                 [:op [:= :add-ref]]
                 [:rel keyword?]
                 [:src ::id]
                 [:dst ::id]]
   ::rm-ref-op [:map
                [:op [:= :rm-ref]]
                [:rel keyword?]
                [:src ::id]
                [:dst ::id]]

   ;; Sugar ops (now with tight, closed shapes via multi-schema)
   ::insert-op [:map
                [:op [:= :insert]]
                [:id ::id]
                [:parent-id ::id]
                [:type {:optional true} keyword?]
                [:props {:optional true} map?]
                [:pos {:optional true} ::pos]]
   ::move-op [:map
              [:op [:= :move]]
              [:id ::id]
              [:from-parent-id {:optional true} ::id]
              [:to-parent-id ::id]
              [:pos {:optional true} ::pos]]
   ::delete-op [:map
                [:op [:= :delete]]
                [:id ::id]]
   ::reorder-op [:map
                 [:op [:= :reorder]]
                 [:id ::id]
                 [:parent-id ::id]
                 [:pos ::pos]]
   ::move-up-op [:map
                 [:op [:= :move-up]]
                 [:id ::id]]
   ::move-down-op [:map
                   [:op [:= :move-down]]
                   [:id ::id]]
   ::sugar-op [:multi {:dispatch :op}
               [:insert ::insert-op]
               [:move ::move-op]
               [:delete ::delete-op]
               [:reorder ::reorder-op]
               [:move-up ::move-up-op]
               [:move-down ::move-down-op]]

   ::op [:or ::create-node-op ::place-op ::update-node-op ::prune-op
         ::add-ref-op ::rm-ref-op ::sugar-op]
   ::tx [:or nil? ::op [:sequential ::op]]

   ::node [:map
           [:type keyword?]
           [:props {:optional true} map?]
           [:sys {:optional true} map?]]

   ::db [:map
         [:version {:optional true} int?]
         [:nodes [:map-of ::id ::node]]
         [:child-ids/by-parent [:map-of ::id [:vector ::id]]]
         [:derived {:optional true} map?]
         [:refs {:optional true} ::edges]
         [:edge-registry {:optional true}
          [:map-of keyword?
           [:map
            [:acyclic? {:optional true} boolean?] ;; forbid cycles over this rel
            [:unique? {:optional true} boolean?] ;; at most one dst per (rel,src)
            [:src-type {:optional true} any?] ;; predicates or keywords you check in op
            [:dst-type {:optional true} any?]]]]
         [:roots {:optional true} [:vector ::id]]]

   ;; Function Schemas for instrumentation
   ::pos->index-fn [:=> [:cat ::db ::id ::id ::pos] int?]
   ::place-args [:map
                 [:id ::id]
                 [:parent-id {:optional true} [:or nil? ::id]]
                 [:pos {:optional true} ::pos]]
   ::place*-fn [:=> [:cat ::db ::place-args] ::db]
   ::apply-tx*-fn [:=> [:cat ::db ::tx [:? [:map
                                            [:derive {:optional true} fn?]
                                            [:assert? {:optional true} boolean?]]]]
                   ::db]})

;; --- Dynamic sugar op schemas ----------------------------------------------

;; Centralized Operation Registry: {op-kw -> {:schema S, :handler F, :axes #{...}, :doc ""}}
(defonce ^:private op-registry* (atom {}))

(defn make-schema
  "Wrap a Malli form with our base registry."
  [form] (m/schema [:schema {:registry registry} form]))

(defn register-op!
  "Idempotently register (or replace) an operation definition."
  [opkw {:keys [schema handler axes doc] :as definition}]
  (assert handler (str "Handler required for op: " opkw))

  ;; Determine the Malli schema object.
  (let [s (cond
            ;; If it's already a schema object, use it.
            (m/schema? schema) schema
            ;; If it's a keyword (e.g., ::create-node-op), assume it's a key in the registry and wrap it.
            (keyword? schema) (make-schema schema)
            ;; If it's a vector (malli form), compile it.
            (vector? schema) (make-schema schema)
            ;; If schema is missing
            (nil? schema) nil
            :else (throw (ex-info "Invalid schema format provided for op" {:op opkw :schema schema})))]

    (swap! op-registry* assoc opkw (assoc definition :schema s :axes (or axes #{}) :doc doc))))

(defn op-definition-for
  "Retrieve the full definition map for an op, or nil if unknown."
  [op] (get @op-registry* op))

(defn registry-entries
  "Get all registry entries for inspection."
  []
  @op-registry*)

(defn op-schema-for
  "Retrieve the compiled Malli schema for an op."
  [op] (:schema (op-definition-for op)))

(def op-schema (m/schema [:schema {:registry registry} ::op]))
(def tx-schema (m/schema [:schema {:registry registry} ::tx]))
(def db-schema (m/schema [:schema {:registry registry} ::db]))

;; --- Gatekeepers --------------------------------------------

(defn- ex! [what schema x]
  (throw (ex-info "Schema validation failed"
                  {:what what
                   :errors (me/humanize (m/explain schema x))})))

(defn validate-op! [x]
  (let [op (:op x)
        ;; Use the centralized lookup
        s (op-schema-for op)]
    ;; This error message is critical for the LLM agent
    (when-not s (throw (ex-info "Unknown :op (Not found in registry)" {:op op})))
    (when (and s (not (m/validate s x)))
      (throw (ex-info "Schema validation failed"
                      {:what :op :errors (me/humanize (m/explain s x))})))))
(defn validate-tx! [x] (when-not (m/validate tx-schema x) (ex! :tx tx-schema x)))
(defn validate-db! [x] (when-not (m/validate db-schema x) (ex! :db db-schema x)))

;; Optional: turn on function instrumentation in dev REPL
(defn instrument! []
  #?(:clj (md/start!))
  (mi/instrument!))
