(ns evolver.invariants
  "Invariant checking for tree kernel. Optional, flagged via assert? parameter.")

(defn check-invariants
  "Verify that derived data is consistent with canonical adjacency.
   Uses Tier-A + (optionally) Tier-B if present."
  [db]
  (when-let [derived (:derived db)]
    (let [{:keys [nodes children-by-parent-id]} db
          {:keys [parent-id-of child-ids-of index-of pre post subtree-size-of
                  id-by-pre reachable-ids orphan-ids]} derived]

      ;; Adjacency symmetry: parent-child relationship is bidirectional
      (doseq [[parent-id child-ids] children-by-parent-id
              child-id child-ids]
        (assert (= parent-id (get parent-id-of child-id))
                (str "Adjacency symmetry broken: " child-id " not parent " parent-id)))

      ;; No duplicate children in any parent's child list
      (doseq [[parent-id child-ids] children-by-parent-id]
        (assert (= (count child-ids) (count (distinct child-ids)))
                (str "Duplicate children in " parent-id ": " child-ids)))

      ;; Subtree size formula: (post - pre + 1) / 2 (if Tier-B present)
      (when subtree-size-of
        (doseq [[id size] subtree-size-of
                :let [pre-val (get pre id)
                      post-val (get post id)]
                :when (and pre-val post-val)]
          (assert (= size (quot (inc (- post-val pre-val)) 2))
                  (str "Subtree size mismatch for " id ": " size " vs " (quot (inc (- post-val pre-val)) 2)))))

      ;; id-by-pre is inverse of pre
      (doseq [[id pre-val] pre]
        (assert (= id (get id-by-pre pre-val))
                (str "pre/id-by-pre mismatch: " id " at pre " pre-val)))

      ;; index-of matches actual positions in children vectors
      (doseq [[parent-id child-ids] children-by-parent-id
              [idx child-id] (map-indexed vector child-ids)]
        (assert (= idx (get index-of child-id))
                (str "Index mismatch for " child-id ": " idx " vs " (get index-of child-id))))

      ;; orphan-ids are exactly those not reachable from root (if Tier-B present)
      (when (and reachable-ids orphan-ids)
        (let [all-node-ids (set (keys nodes))
              expected-orphans (set (remove reachable-ids all-node-ids))]
          (assert (= orphan-ids expected-orphans)
                  (str "Orphan-ids vs reachable mismatch: " orphan-ids " vs " expected-orphans))))

      true)))