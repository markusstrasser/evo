(ns labs.graph.validate
  "Edge-level constraint validation.
   Returns findings without mutating state."
  (:require [labs.graph.derive :as derive]))

(defn unknown-targets
  "Find refs pointing to non-existent nodes."
  [db]
  (let [nodes (-> db :nodes keys set)
        edges (derive/edge-index db)]
    (vec
     (for [[rel src-map] edges
           [src dsts] src-map
           dst dsts
           :when (not (contains? nodes dst))]
       {:issue :graph/unknown-target
        :rel rel
        :src src
        :dst dst}))))

(defn cycles
  "Find cycles in a specific relation (when acyclic constraint desired)."
  [db relation]
  (let [edges (derive/edge-index db)
        rel-edges (get edges relation {})]
    (vec
     (for [node (keys rel-edges)
           :when (derive/reachable? db node node relation)]
       {:issue :graph/cycle-detected
        :rel relation
        :node node}))))

(defn duplicate-relations
  "Find nodes with duplicate outgoing relations (when unique constraint desired)."
  [db relation]
  (let [edges (derive/edge-index db)
        rel-edges (get edges relation {})]
    (vec
     (for [[src dsts] rel-edges
           :when (> (count dsts) 1)]
       {:issue :graph/duplicate-relation
        :rel relation
        :src src
        :dsts dsts}))))

(defn validate-constraints
  "Run all constraint checks, return findings.
   Options:
   - :unique-relations #{:parent :contains} - rels that should have max 1 target
   - :acyclic-relations #{:parent :depends-on} - rels that should be cycle-free"
  [db {:keys [unique-relations acyclic-relations] :or {unique-relations #{} acyclic-relations #{}}}]
  (concat
   (unknown-targets db)
   (mapcat (partial cycles db) acyclic-relations)
   (mapcat (partial duplicate-relations db) unique-relations)))