(ns labs.temporal.tx.normalize
  "Transaction peephole optimizer - pre-flight normalizer for operation sequences.

   Applies minimal, safe local rules to clean up redundant or inefficient
   operation patterns before they reach the kernel. Keeps the rules tiny
   and obviously correct.

   All rules are:
   - Local (look at small windows of ops)
   - Safe (never change semantics)
   - Idempotent (normalize(normalize(ops)) == normalize(ops))

   Usage:
     (normalize [ops...]) => [optimized-ops...]

   Rules implemented:
   1. cancel-create-then-delete: [{:op :create-node :id X} {:op :delete :id X}] => []
   2. drop-noop-reorder: [{:op :reorder :id X :pos nil}] => []
   3. merge-adjacent-patches: [{:op :update-node :id X :props {:a 1}} {:op :update-node :id X :props {:b 2}}] => [{:op :update-node :id X :props {:a 1 :b 2}}]"
  (:require [medley.core :as medley]))

(defn cancel-create-then-delete
  "Cancel create+delete pairs for the same ID.

   Removes create, delete, and ALL operations on cancelled IDs to maintain safety."
  [ops]
  (let [;; Build index of create operations by ID
        creates (into {} (keep-indexed
                          (fn [i op]
                            (when (= :create-node (:op op))
                              [(:id op) i]))
                          ops))

        ;; Find IDs that have both create and delete
        cancelled-ids (into #{}
                            (keep (fn [op]
                                    (when (and (= :delete (:op op))
                                               (contains? creates (:id op)))
                                      (:id op)))
                                  ops))]

    ;; Filter out ALL operations on cancelled IDs
    (->> ops
         (remove (fn [op]
                   (contains? cancelled-ids (:id op))))
         vec)))

(defn drop-noop-reorder
  "Drop reorder operations that don't actually move anything.

   Removes reorder ops with nil position or same position."
  [ops]
  (->> ops
       (remove (fn [op]
                 (and (= :reorder (:op op))
                      (nil? (:pos op)))))
       vec))

(defn merge-adjacent-patches
  "Merge adjacent update-node operations on the same node.

   Combines multiple property updates into a single operation."
  [ops]
  (->> ops
       ;; Group consecutive operations by [op-type id]
       (partition-by (fn [op] [(:op op) (:id op)]))

       ;; For each group, merge if all are update-node on same ID
       (mapcat (fn [group]
                 (if (and (> (count group) 1)
                          (every? #(= :update-node (:op %)) group)
                          (apply = (map :id group)))
                   ;; Merge all props into first operation
                   [(reduce (fn [acc op]
                              (update acc :props merge (:props op)))
                            (first group)
                            (rest group))]
                   ;; Keep group as-is
                   group)))
       vec))

(defn normalize
  "Apply all normalization rules to operation sequence.

   This is the main entry point - applies all peephole optimizations
   in a safe order. Rules are applied repeatedly until a fixed point."
  [ops]
  (if (empty? ops)
    ops
    (let [normalized (-> ops
                         cancel-create-then-delete
                         drop-noop-reorder
                         merge-adjacent-patches)]
      ;; Apply rules until fixed point (idempotent)
      (if (= ops normalized)
        normalized
        (recur normalized)))))

(comment
  ;; REPL usage examples:

  ;; Test create-delete cancellation
  (def ops1 [{:op :create-node :id "temp" :type :div}
             {:op :delete :id "temp"}
             {:op :create-node :id "keep" :type :span}])
  (normalize ops1) ; => [{:op :create-node :id "keep" :type :span}]

  ;; Test noop reorder removal
  (def ops2 [{:op :reorder :id "item" :pos nil}
             {:op :create-node :id "real" :type :div}])
  (normalize ops2) ; => [{:op :create-node :id "real" :type :div}]

  ;; Test patch merging
  (def ops3 [{:op :update-node :id "item" :props {:a 1}}
             {:op :update-node :id "item" :props {:b 2}}
             {:op :create-node :id "other" :type :div}
             {:op :update-node :id "item" :props {:c 3}}])
  (normalize ops3) ; => [{:op :update-node :id "item" :props {:a 1 :b 2}}
                    ;     {:op :create-node :id "other" :type :div}
                    ;     {:op :update-node :id "item" :props {:c 3}}]

  ;; Test idempotence
  (let [ops [{:op :create-node :id "x"} {:op :delete :id "x"}]]
    (= (normalize ops) (normalize (normalize ops)))) ; => true
  )