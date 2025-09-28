(ns kernel.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            [malli.dev :as md]))

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
         [:nodes [:map-of ::id ::node]]
         [:child-ids/by-parent [:map-of ::id [:vector ::id]]]
         [:derived {:optional true} map?]
         [:refs {:optional true} ::edges]
         [:edge-registry {:optional true}
          [:map-of keyword?
           [:map
            [:acyclic? {:optional true} :boolean] ;; forbid cycles over this rel
            [:unique? {:optional true} :boolean] ;; at most one dst per (rel,src)
            [:src-type {:optional true} any?] ;; predicates or keywords you check in op
            [:dst-type {:optional true} any?]]]]
         [:roots {:optional true} [:vector ::id]]]

   ;; Function Schemas for instrumentation
   ::pos->index-fn [:=> [:cat ::db ::id ::id ::pos] int?]
   ::set-parent-args [:map
                      [:id ::id]
                      [:parent-id {:optional true} [:or nil? ::id]]
                      [:pos {:optional true} ::pos]]
   ::set-parent*-fn [:=> [:cat ::db ::set-parent-args] ::db]
   ::interpret*-fn [:=> [:cat ::db ::tx [:? [:map
                                             [:derive {:optional true} fn?]
                                             [:assert? {:optional true} :boolean]]]]
                    ::db]})

(def op-schema (m/schema [:schema {:registry registry} ::op]))
(def tx-schema (m/schema [:schema {:registry registry} ::tx]))
(def db-schema (m/schema [:schema {:registry registry} ::db]))

;; --- Gatekeepers --------------------------------------------

(defn- ex! [what schema x]
  (throw (ex-info "Schema validation failed"
                  {:what what
                   :errors (me/humanize (m/explain schema x))})))

(defn validate-op! [x] (when-not (m/validate op-schema x) (ex! :op op-schema x)))
(defn validate-tx! [x] (when-not (m/validate tx-schema x) (ex! :tx tx-schema x)))
(defn validate-db! [x] (when-not (m/validate db-schema x) (ex! :db db-schema x)))

;; Optional: turn on function instrumentation in dev REPL
(defn instrument! []
  (md/start!)
  (mi/instrument!))