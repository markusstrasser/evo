(ns labs.graph.derive
  "Build adjacency indexes from node :props/:refs. 
   Refs are derived state - nodes are the single source of truth."
  (:require [clojure.set :as set]))

(defn edge-index
  "Extract edge adjacency map from db nodes.
   Returns {relation {src-id #{dst-id}}} structure."
  [db]
  (reduce-kv
   (fn [E id {:keys [props]}]
     (reduce-kv (fn [E rel dsts]
                  (assoc-in E [rel id] (set dsts)))
                E (get-in props [:refs] {})))
   {} (:nodes db)))

(defn backref-index
  "Build reverse adjacency map for efficient backref queries.
   Returns {relation {dst-id #{src-id}}} structure."
  [db]
  (let [forward (edge-index db)]
    (reduce-kv
     (fn [B rel src-map]
       (reduce-kv
        (fn [B src dsts]
          (reduce (fn [B dst]
                    (update-in B [rel dst] (fnil conj #{}) src))
                  B dsts))
        B src-map))
     {} forward)))

(defn neighbors
  "Get all outgoing neighbors for a node in a specific relation."
  [db node-id relation]
  (get-in (edge-index db) [relation node-id] #{}))

(defn backrefs
  "Get all incoming neighbors for a node in a specific relation."
  [db node-id relation]
  (get-in (backref-index db) [relation node-id] #{}))

(defn reachable?
  "Check if dst is reachable from src via relation (transitive closure)."
  [db src dst relation]
  (let [edges (edge-index db)]
    (loop [visited #{}
           queue #{src}]
      (if (empty? queue)
        false
        (let [current (first queue)
              rest-queue (disj queue current)]
          (if (= current dst)
            true
            (if (visited current)
              (recur visited rest-queue)
              (recur (conj visited current)
                     (into rest-queue (get-in edges [relation current] #{}))))))))))