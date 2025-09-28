(ns kernel.invariants
  "Invariant checking for tree kernel. Optional, flagged via assert? parameter.")

(defn check-invariants [db]
  (when-let [derived (:derived db)]
    (let [nodes (:nodes db)
          child-ids-by-parent (:child-ids/by-parent db)
          {:keys [parent-id-of child-ids-of index-of pre post subtree-size-of
                  id-by-pre reachable-ids orphan-ids]} derived

          all-children (into #{} (mapcat second child-ids-by-parent))]

      ;; NEW: All roots must exist and never be anyone's child
      (let [roots (or (:roots db) ["root"])]
        (doseq [r roots]
          (assert (contains? nodes r) (str "Root missing: " r))
          (assert (not (contains? all-children r))
                  (str "Root listed as a child: " r))))

      ;; NEW: every child referenced exists in :nodes
      (doseq [[parent-id child-ids] child-ids-by-parent
              child-id child-ids]
        (assert (contains? nodes child-id)
                (str "Child id missing from :nodes: " child-id " (parent " parent-id ")")))

      ;; Adjacency symmetry ...
      (doseq [[parent-id child-ids] child-ids-by-parent
              child-id child-ids]
        (assert (= parent-id (get parent-id-of child-id))
                (str "Adjacency symmetry broken: " child-id " not parent " parent-id)))

      ;; NEW: no self-parenting
      (doseq [[id p] parent-id-of]
        (assert (not= id p) (str "Self-parenting detected at " id)))

      ;; No duplicate children per parent
      (doseq [[parent-id child-ids] child-ids-by-parent]
        (assert (= (count child-ids) (count (distinct child-ids)))
                (str "Duplicate children in " parent-id ": " child-ids)))

;; Subtree size (if available)
      (when subtree-size-of
        (doseq [[id size] subtree-size-of
                :let [pre-val (get pre id)
                      post-val (get post id)]
                :when (and pre-val post-val)]
          (assert (= size (quot (inc (- post-val pre-val)) 2))
                  (str "Subtree size mismatch for " id))))

      ;; id-by-pre inverse
      (doseq [[id pre-val] pre]
        (assert (= id (get id-by-pre pre-val))
                (str "pre/id-by-pre mismatch for " id " at " pre-val)))

      ;; index-of matches sibling positions
      (doseq [[parent-id child-ids] child-ids-by-parent
              [idx child-id] (map-indexed vector child-ids)]
        (assert (= idx (get index-of child-id))
                (str "Index mismatch for " child-id ": " idx " vs " (get index-of child-id))))

      ;; orphan-ids bookkeeping (if present)
      (when (and reachable-ids orphan-ids)
        (let [all-node-ids (set (keys nodes))
              expected-orphans (set (remove reachable-ids all-node-ids))]
          (assert (= orphan-ids expected-orphans)
                  (str "Orphan-ids vs reachable mismatch: " orphan-ids " vs " expected-orphans))))

      ;; Edge invariants
      (when-let [E (:refs db)]
        (doseq [[rel m] E, [src dsts] m, dst dsts]
          (assert (contains? nodes src) (str "edge src missing: " rel " " src))
          (assert (contains? nodes dst) (str "edge dst missing: " rel " " dst))
          (assert (not= src dst) (str "self-edge on " rel " " src))))

      true)))