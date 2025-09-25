;; Debug helpers for efficient troubleshooting
;; Reduces state exploration by providing targeted insights

(ns agent.debug-helpers)

;; Compact db diff for change tracking
(defn db-diff
  "Calculate differences between two database states.

  Args:
    before: Database state before changes
    after: Database state after changes

  Returns:
    Map with change counts and summary string."
  [before after]
  (let [node-diff (clojure.set/difference (set (keys (:nodes after)))
                                         (set (keys (:nodes before))))
        children-diff (filter #(not= (get (:children-by-parent before) %)
                                    (get (:children-by-parent after) %))
                             (keys (:children-by-parent after)))
        view-diff (filter #(not= (get (:view before) %)
                                (get (:view after) %))
                         (keys (:view after)))]
    {:nodes-added (count node-diff)
     :children-changed (count children-diff)
     :view-changed (count view-diff)
     :tx-log-growth (- (count (:tx-log after)) (count (:tx-log before)))
     :summary (str "Nodes: +" (count node-diff)
                   ", Children: " (count children-diff) " changed"
                   ", View: " (count view-diff) " changed"
                   ", TX: +" (- (count (:tx-log after)) (count (:tx-log before))))}))

;; Operation success checker
(defn check-operation-result
  "Check if an operation was successful by comparing db states.

  Args:
    db-before: Database before operation
    db-after: Database after operation
    operation: Operation that was performed

  Returns:
    Map with :operation, :success?, :changes, :status (:success/:partial/:no-op)."
  [db-before db-after operation]
  (let [diff (db-diff db-before db-after)
        has-changes? (or (> (:nodes-added diff) 0)
                        (> (:children-changed diff) 0)
                        (> (:view-changed diff) 0))
        has-logs? (> (:tx-log-growth diff) 0)]
    {:operation operation
     :success? (and has-changes? has-logs?)
     :changes diff
     :status (cond
               (and has-changes? has-logs?) :success
               has-changes? :partial
               :else :no-op)}))

;; Quick error scanner
(defn scan-for-errors
  "Scan database for errors and inconsistencies.

  Args:
    db: Database to scan

  Returns:
    Map with :error-count, :invalid-nodes, :orphan-nodes, :healthy?, :issues."
  [db]
  (let [error-logs (filter #(= (:level %) :error) (:log-history db))
        invalid-nodes (filter #(not (agent.code-analysis/validate-db-structure {:nodes {% (:nodes db)}})) (keys (:nodes db)))
        orphan-nodes         (filter #(and (not= % "root") (not (contains? (:children-by-parent db) %))) (keys (:nodes db)))]
    {:error-count (count error-logs)
     :invalid-nodes (count invalid-nodes)
     :orphan-nodes (count orphan-nodes)
     :healthy? (and (zero? (count error-logs))
                   (zero? (count invalid-nodes))
                   (zero? (count orphan-nodes)))
     :issues (cond-> []
               (seq error-logs) (conj (str (count error-logs) " errors in log"))
               (seq invalid-nodes) (conj (str (count invalid-nodes) " invalid nodes"))
               (seq orphan-nodes) (conj (str (count orphan-nodes) " orphan nodes")))}))

;; State consistency checker
(defn check-consistency
  "Check database for structural consistency.

  Args:
    db: Database to check

  Returns:
    Map with :orphaned-children, :missing-parents, :consistent?, :issues."
  [db]
  (let [all-children (set (mapcat val (:children-by-parent db)))
        all-nodes (set (keys (:nodes db)))
        orphan-children (clojure.set/difference all-children all-nodes)
        missing-parents (filter #(not (or (= % "root") (contains? (:nodes db) %)))
                               (keys (:children-by-parent db)))]
    {:orphaned-children (count orphan-children)
     :missing-parents (count missing-parents)
     :consistent? (and (empty? orphan-children) (empty? missing-parents))
     :issues (cond-> []
               (seq orphan-children) (conj (str "Orphaned children: " orphan-children))
               (seq missing-parents) (conj (str "Missing parents: " missing-parents)))}))