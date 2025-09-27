(ns evolver.schemas
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

;; --- Registry ------------------------------------------------

(def registry
  {::id [:string {:min 1}]
   ::pos [:or nil?
          [:enum :first :last]
          [:tuple [:= :index] int?]
          [:tuple [:= :before] ::id]
          [:tuple [:= :after] ::id]]

   ;; Core ops (closed)
   ::ensure-node-op [:map
                     [:op [:= :ensure-node]]
                     [:id ::id]
                     [:type {:optional true} keyword?]
                     [:props {:optional true} map?]]
   ::set-parent-op [:map
                    [:op [:= :set-parent]]
                    [:id ::id]
                    [:parent-id {:optional true} [:or nil? ::id]]
                    [:pos {:optional true} ::pos]]
   ::patch-props-op [:map
                     [:op [:= :patch-props]]
                     [:id ::id]
                     [:props {:optional true} map?]
                     [:sys {:optional true} map?]
                     [:updates {:optional true} map?]]
   ::purge-op [:map
               [:op [:= :purge]]
               [:pred fn?]]

   ;; Sugar ops (open maps so extra keys are allowed)
   ::sugar-op [:map {:closed false}
               [:op [:enum :ins :mv :del :reorder :move-up :move-down]]]

   ::op [:or ::ensure-node-op ::set-parent-op ::patch-props-op ::purge-op ::sugar-op]
   ::tx [:or nil? ::op [:sequential ::op]]

   ::node [:map
           [:type keyword?]
           [:props {:optional true} map?]
           [:sys {:optional true} map?]]

   ::db [:map
         [:nodes [:map-of ::id ::node]]
         [:children-by-parent-id [:map-of ::id [:vector ::id]]]
         [:derived {:optional true} map?]
         [:edges {:optional true} map?]]})

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
(defn instrument! [] (mi/instrument!))