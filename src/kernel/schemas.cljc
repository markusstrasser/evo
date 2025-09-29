(ns kernel.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            #?(:clj [malli.dev :as md])))

;; --- Registry ------------------------------------------------

(def registry
  {::node-id [:string {:min 1}]
   ::anchor [:or nil?
             [:enum :first :last]
             [:tuple [:= :index] int?]
             [:tuple [:= :before-id] ::node-id]
             [:tuple [:= :after-id] ::node-id]]

   ;; Core ops (closed)
   ::create-node-op [:map
                     [:op [:= :create-node]]
                     [:node-id ::node-id]
                     [:node-type {:optional true} keyword?]
                     [:props {:optional true} map?]]
   ::place-op [:map
               [:op [:= :place]]
               [:node-id ::node-id]
               [:parent-id {:optional true} [:or nil? ::node-id]]
               [:anchor {:optional true} ::anchor]]
   ::update-node-op [:map
                     [:op [:= :update-node]]
                     [:node-id ::node-id]
                     [:props {:optional true} map?]
                     [:sys {:optional true} map?]
                     [:updates {:optional true} map?]]
   ::prune-op [:map
               [:op [:= :prune]]
               [:pred fn?]]

   ;; Edges primitive ops
   ::edges [:map-of keyword? [:map-of ::node-id [:set ::node-id]]]
   ::add-ref-op [:map
                 [:op [:= :add-ref]]
                 [:relation keyword?]
                 [:source-id ::node-id]
                 [:target-id ::node-id]]
   ::rm-ref-op [:map
                [:op [:= :rm-ref]]
                [:relation keyword?]
                [:source-id ::node-id]
                [:target-id ::node-id]]

   ;; Sugar ops (now with tight, closed shapes via multi-schema)
   ::insert-op [:map
                [:op [:= :insert]]
                [:node-id ::node-id]
                [:parent-id ::node-id]
                [:node-type {:optional true} keyword?]
                [:props {:optional true} map?]
                [:anchor {:optional true} ::anchor]]
   ::move-op [:map
              [:op [:= :move]]
              [:node-id ::node-id]
              [:from-parent-id {:optional true} ::node-id]
              [:target-parent-id ::node-id]
              [:anchor {:optional true} ::anchor]]
   ::delete-op [:map
                [:op [:= :delete]]
                [:node-id ::node-id]]
   ::reorder-op [:map
                 [:op [:= :reorder]]
                 [:node-id ::node-id]
                 [:parent-id ::node-id]
                 [:anchor ::anchor]]
   ::move-up-op [:map
                 [:op [:= :move-up]]
                 [:node-id ::node-id]]
   ::move-down-op [:map
                   [:op [:= :move-down]]
                   [:node-id ::node-id]]
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
           [:node-type keyword?]
           [:props {:optional true} map?]
           [:sys {:optional true} map?]]

   ::db [:map
         [:version {:optional true} int?]
         [:nodes [:map-of ::node-id ::node]]
         [:children-by-parent-id [:map-of ::node-id [:vector ::node-id]]]
         [:derived {:optional true} map?]
         [:refs {:optional true} ::edges]
         [:edge-registry {:optional true}
          [:map-of keyword?
           [:map
            [:acyclic? {:optional true} boolean?] ;; forbid cycles over this rel
            [:unique? {:optional true} boolean?] ;; at most one dst per (rel,src)
            [:src-type {:optional true} any?] ;; predicates or keywords you check in op
            [:dst-type {:optional true} any?]]]]
         [:roots {:optional true} [:vector ::node-id]]]

   ;; Function Schemas for instrumentation
   ::pos->index-fn [:=> [:cat ::db ::node-id ::node-id ::anchor] int?]
   ::place-args [:map
                 [:node-id ::node-id]
                 [:parent-id {:optional true} [:or nil? ::node-id]]
                 [:anchor {:optional true} ::anchor]]
   ::place*-fn [:=> [:cat ::db ::place-args] ::db]
   ::apply-tx+effects*-fn [:=> [:cat ::db ::tx [:? [:map
                                                    [:assert? {:optional true} boolean?]
                                                    [:pipeline {:optional true} vector?]
                                                    [:trace? {:optional true} boolean?]]]]
                           [:map
                            [:db ::db]
                            [:effects vector?]
                            [:error {:optional true} map?]
                            [:findings {:optional true} vector?]
                            [:trace {:optional true} vector?]]]})

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
