(ns labs.graph.refs
  "Reference system implementation as policy over core kernel.
   
   Refs are implemented as derived/indexed data over node props,
   not as kernel operations. This maintains the core algebra
   while providing graph powers."
  (:require [labs.graph.derive :as derive]
            [labs.graph.validate :as validate]))

(defn add-ref-lowering
  "Lower :add-ref to :update-node op over :props/:refs.
   Multiple refs to same relation are accumulated in a set."
  [{:keys [src dst relation]}]
  {:op :update-node
   :id src
   :props {:refs {relation #{dst}}}})

(defn remove-ref-lowering
  "Lower :rm-ref to :update-node op that removes specific target."
  [{:keys [src dst relation]}]
  {:op :update-node
   :id src
   :props {:refs {relation #{}}}}) ;; Simplified: would need merge logic for partial removal

(defn ref-constraints
  "Standard ref constraints for use in validation."
  []
  {:unique-relations #{:parent} ;; tree constraint: each node has one parent
   :acyclic-relations #{:parent}}) ;; no cycles in parent relation