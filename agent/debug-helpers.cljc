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

;; Keyboard event debugging helpers
(defn debug-keyboard-mapping
  "Debug a specific keyboard mapping to see if it matches an event

  Args:
    mapping: Keyboard mapping from evolver.keyboard/keyboard-mappings
    event-props: Map with :key, :shiftKey, :ctrlKey, :altKey, :metaKey

  Returns:
    Map with :matches?, :reason, :mapping-details"
  [mapping event-props]
  (let [key-matches (= (:key event-props) (:key (:keys mapping)))
        shift-matches (= (:shiftKey event-props) (:shift (:keys mapping)))
        ctrl-matches (= (:ctrlKey event-props) (:ctrl (:keys mapping)))
        alt-matches (= (:altKey event-props) (:alt (:keys mapping)))
        meta-matches (= (:metaKey event-props) (:meta (:keys mapping)))
        all-match (and key-matches shift-matches ctrl-matches alt-matches meta-matches)]
    {:matches? all-match
     :reason (if all-match
               "All modifiers match"
               (str "Mismatch: "
                    (cond-> []
                      (not key-matches) (conj "key")
                      (not shift-matches) (conj "shift")
                      (not ctrl-matches) (conj "ctrl")
                      (not alt-matches) (conj "alt")
                      (not meta-matches) (conj "meta"))
                    " don't match"))
     :mapping-details {:action (:action mapping)
                       :requires-selection (:requires-selection mapping)
                       :expected-keys (:keys mapping)
                       :actual-keys event-props}}))

(defn debug-keyboard-event
  "Debug why a keyboard event was or wasn't handled

  Args:
    event-props: Map with :key, :shiftKey, :ctrlKey, :altKey, :metaKey
    db: Current database state

  Returns:
    Map with :handled?, :matching-mappings, :selection-state, :debug-info"
  [event-props db]
  (let [selection-state {:selected-set (:selected (:view db))
                         :selected-count (count (:selected (:view db)))}
        mappings (eval '(evolver.keyboard/keyboard-mappings)) ; This would need to be available
        matching-mappings (filter #(and ((:key-matches evolver.keyboard/key-matches) % event-props)
                                       (or (not (:requires-selection %))
                                           (not-empty (:selected-set selection-state))))
                                 mappings)]
    {:handled? (seq matching-mappings)
     :matching-mappings (map #(select-keys % [:action :keys :requires-selection]) matching-mappings)
     :selection-state selection-state
     :debug-info {:total-mappings (count mappings)
                  :event-props event-props
                  :has-selection? (not-empty (:selected-set selection-state))}}))

(defn trace-keyboard-operation
  "Trace the execution of a keyboard operation

  Args:
    operation: Keyword like :delete, :create-child, etc.
    db-before: Database state before operation
    db-after: Database state after operation

  Returns:
    Map with operation analysis and state changes"
  [operation db-before db-after]
  (let [diff (db-diff db-before db-after)
        selection-before (:selected (:view db-before))
        selection-after (:selected (:view db-after))
        selection-changed? (not= selection-before selection-after)]
    {:operation operation
     :success? (case operation
                 :delete (> (:nodes-added diff) 0) ; Negative growth means nodes removed
                 :create-child (> (:nodes-added diff) 0)
                 :create-sibling (> (:nodes-added diff) 0)
                 :navigation selection-changed?
                 :undo (and (> (:tx-log-growth diff) 0) selection-changed?)
                 :redo (and (> (:tx-log-growth diff) 0) selection-changed?)
                 false)
     :state-changes {:selection-changed selection-changed?
                     :selection-before selection-before
                     :selection-after selection-after
                     :db-diff diff}
     :prerequisites-met? (case operation
                           :delete (not-empty selection-before)
                           :create-child (= (count selection-before) 1)
                           :create-sibling (= (count selection-before) 1)
                           :add-reference (= (count selection-before) 2)
                           :remove-reference (= (count selection-before) 2)
                           true)}))

(defn keyboard-debug-summary
  "Generate a comprehensive keyboard debugging summary

  Args:
    db: Current database state

  Returns:
    Map with keyboard system status and potential issues"
  [db]
  (let [selection (:selected (:view db))
        mappings-count (count (eval '(evolver.keyboard/keyboard-mappings)))
        undo-available (seq (:undo-stack db))
        redo-available (seq (:redo-stack db))]
    {:selection-status {:has-selection (not-empty selection)
                        :selection-count (count selection)
                        :selected-nodes selection}
     :keyboard-system {:mappings-loaded mappings-count
                       :system-active (> mappings-count 0)}
     :undo-redo-status {:can-undo undo-available
                        :can-redo redo-available
                        :undo-count (count (:undo-stack db))
                        :redo-count (count (:redo-stack db))}
     :potential-issues (cond-> []
                         (empty? selection) (conj "No nodes selected - some operations unavailable")
                         (not undo-available) (conj "No undo history available")
                         (zero? mappings-count) (conj "Keyboard mappings not loaded"))}))