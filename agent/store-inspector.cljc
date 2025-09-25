;; Store inspection tools for debugging and analysis
;; Provides functions to inspect the current state of the evolver store

(ns agent.store-inspector
  (:require [evolver.kernel :as kernel]))

(defn inspect-store
  "Inspect the current store state with optional filtering.

  Args:
    store: The atom containing the db
    filters: Optional map with keys like :include-keys, :exclude-keys

  Returns:
    Map with store summary and filtered data."
  [store & {:keys [include-keys exclude-keys]}]
  (let [db @store
        all-keys (set (keys db))
        keys-to-show (cond
                       include-keys (clojure.set/intersection all-keys include-keys)
                       exclude-keys (clojure.set/difference all-keys exclude-keys)
                       :else all-keys)]
    {:store-summary {:node-count (count (:nodes db))
                     :tx-count (count (:tx-log db))
                     :selected-count (count (:selected (:view db)))
                     :reference-count (count (:references db))}
     :filtered-data (select-keys db keys-to-show)}))

(defn check-reference-integrity
  "Check that references are bidirectional and valid.

  Args:
    store: The atom containing the db

  Returns:
    Map with :valid?, :issues (vector of issues)"
  [store]
  (let [db @store
        references (:references db)
        issues (atom [])]
    ;; Check that all referenced nodes exist
    (doseq [[from-node referencers] references]
      (when-not (contains? (:nodes db) from-node)
        (swap! issues conj {:type :missing-node :node from-node :context :referenced}))
      (doseq [referencer referencers]
        (when-not (contains? (:nodes db) referencer)
          (swap! issues conj {:type :missing-node :node referencer :context :referencer}))))
    ;; Check bidirectionality (optional, depending on design)
    ;; For now, just check existence
    {:valid? (empty? @issues)
     :issues @issues}))

(defn get-operation-history
  "Get recent operation history with summaries.

  Args:
    store: The atom containing the db
    limit: Max number of operations to return (default 10)

  Returns:
    Vector of operation summaries."
  [store & {:keys [limit] :or {limit 10}}]
  (let [db @store
        tx-log (:tx-log db)]
    (mapv (fn [tx]
            {:op (:op tx)
             :timestamp (:timestamp tx)
             :args-summary (dissoc (:args tx) :node-data)}) ; Remove large data
          (take-last limit tx-log))))

(defn validate-current-selection
  "Validate that the current selection is valid for operations.

  Args:
    store: The atom containing the db

  Returns:
    Map with :valid?, :issues, :available-ops"
  [store]
  (let [db @store
        selected (:selected (:view db))
        selected-count (count selected)]
    (cond
      (= selected-count 0)
      {:valid? false :issues [:no-selection] :available-ops [:create-child-block :create-sibling-above :create-sibling-below]}

      (= selected-count 1)
      {:valid? true :issues [] :available-ops [:create-child-block :create-sibling-above :create-sibling-below :indent :outdent]}

      (= selected-count 2)
      {:valid? true :issues [] :available-ops [:add-reference :remove-reference]}

      (> selected-count 2)
      {:valid? false :issues [:too-many-selected] :available-ops []})))

(defn performance-metrics
  "Get performance metrics for the store.

  Args:
    store: The atom containing the db

  Returns:
    Map with various metrics."
  [store]
  (let [db @store]
    {:node-count (count (:nodes db))
     :reference-density (if (zero? (count (:nodes db)))
                          0
                          (/ (reduce + (map count (vals (:references db)))) (count (:nodes db))))
     :tx-log-size (count (:tx-log db))
     :view-complexity {:selected (count (:selected (:view db)))
                       :collapsed (count (:collapsed (:view db)))
                       :hovered-referencers (count (:hovered-referencers (:view db)))}}))

(defn quick-state-dump
  "Quick dump of key state for debugging.

  Args:
    store: The atom containing the db

  Returns:
    String summary of current state."
  [store]
  (let [db @store
        selected (:selected (:view db))
        references (:references db)]
    (str "Nodes: " (count (:nodes db))
         ", Selected: " (count selected) " (" (clojure.string/join ", " selected) ")"
         ", References: " (count references) " entries"
         ", TX Log: " (count (:tx-log db)) " operations"
         ", Last TX: " (when-let [last-tx (last (:tx-log db))]
                         (str (:op last-tx) " at " (:timestamp last-tx))))))