(ns kernel.introspect
  "REPL-first introspection tools for kernel DBs.

   Pure, data-focused utilities that provide visibility into DB changes
   and structure without touching core evaluation logic.

   Usage:
     (require '[kernel.introspect :as ix])
     (ix/diff db0 db1)         ; structural deltas
     (ix/path db \"node-id\")    ; breadcrumb from root
     (ix/trace trace-result)   ; extract op-by-op story"
  (:require [clojure.set :as set]))

(defn diff
  "Return concise structural deltas between db0 and db1.

   Returns map with keys:
   - :added      - set of node IDs added in db1
   - :removed    - set of node IDs removed from db0
   - :moved      - set of node IDs that changed parent
   - :props-changed - set of node IDs with different props"
  [db0 db1]
  (let [ids0 (set (keys (:nodes db0)))
        ids1 (set (keys (:nodes db1)))
        added (set/difference ids1 ids0)
        removed (set/difference ids0 ids1)
        parent0 (get-in db0 [:derived :parent-id-of])
        parent1 (get-in db1 [:derived :parent-id-of])
        moved   (->> (set/intersection ids0 ids1)
                     (filter #(not= (parent0 %) (parent1 %)))
                     set)
        props-changed
        (->> (set/intersection ids0 ids1)
             (filter #(not= (get-in db0 [:nodes % :props])
                            (get-in db1 [:nodes % :props])))
             set)]
    {:added added
     :removed removed
     :moved moved
     :props-changed props-changed}))

(defn path
  "Return vector of {:id ... :children [...]} from root to id.

   Provides contextual breadcrumb using derived maps (:parent-id-of, :child-ids-of).
   Uses first root from :roots or defaults to \"root\"."
  [db id]
  (let [pid (get-in db [:derived :parent-id-of])
        cid (get-in db [:derived :child-ids-of])
        ;; choose a root (default \"root\" or first in :roots)
        root (or (-> db :roots first) "root")
        up (loop [x id acc []]
             (if x (recur (pid x) (conj acc x)) acc))
        chain (reverse up)]
    (mapv (fn [i] {:id i :children (vec (cid i []))})
          (cons root (rest chain)))))

(defn trace
  "Extract lightweight step info from {:trace ...} return.

   Takes result from apply-tx+effects* with :trace? true and returns
   vector of step summaries with :i, :op keyword, :effects list, :node-count."
  [{:keys [trace]}]
  (mapv (fn [{:keys [i op effects db]}]
          {:i i
           :op (:op op)
           :effects (map :effect effects)
           :node-count (count (get db :nodes))})
        trace))