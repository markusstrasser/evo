(ns agent.reference_tools
  "Tools for debugging and inspecting the reference system")

(defn inspect-references
  "Inspect all references in the current database"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)]
    {:total-references (count (:references db))
     :referenced-nodes (keys (:references db))
     :reference-graph (:references db)}))

(defn find-orphaned-references
  "Find references to nodes that don't exist"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        all-nodes (set (keys (:nodes db)))
        references (:references db)]
    (into {}
          (for [[target referencers] references
                :when (not (contains? all-nodes target))]
            [target referencers]))))

(defn validate-reference-integrity
  "Check that all references point to valid nodes"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        all-nodes (set (keys (:nodes db)))
        references (:references db)
        invalid-references (find-orphaned-references)]
    {:valid? (empty? invalid-references)
     :invalid-references invalid-references
     :total-references (count references)
     :valid-references (- (count references) (count invalid-references))}))

(defn simulate-reference-hover
  "Simulate hovering over a node to see what gets highlighted"
  [node-id]
  (let [db @(requiring-resolve 'evolver.kernel/db)]
    {:node-id node-id
     :referencers ((requiring-resolve 'evolver.kernel/get-references) db node-id)
     :exists? (contains? (:nodes db) node-id)}))

(defn reference-stats
  "Get statistics about the reference system"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        references (:references db)]
    {:total-referenced-nodes (count references)
     :total-references (reduce + (map count (vals references)))
     :most-referenced (when (seq references)
                        (let [sorted (sort-by #(count (val %)) > references)]
                          {:node-id (first (first sorted))
                           :count (count (val (first sorted)))}))
     :nodes-with-no-references (count (filter #(empty? ((requiring-resolve 'evolver.kernel/get-references) db %))
                                              (keys (:nodes db))))}))

(defn test-reference-operations
  "Test adding and removing references"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        test-node-1 "title"
        test-node-2 "p1-select"]
    (when (and (contains? (:nodes db) test-node-1)
               (contains? (:nodes db) test-node-2))
      (let [add-result ((requiring-resolve 'evolver.kernel/safe-apply-command)
                        db {:op :add-reference :from-node-id test-node-1 :to-node-id test-node-2})
            references-after ((requiring-resolve 'evolver.kernel/get-references) add-result test-node-2)
            remove-result ((requiring-resolve 'evolver.kernel/safe-apply-command)
                           add-result {:op :remove-reference :from-node-id test-node-1 :to-node-id test-node-2})
            references-final ((requiring-resolve 'evolver.kernel/get-references) remove-result test-node-2)]
        {:add-success? (contains? references-after test-node-1)
         :remove-success? (not (contains? references-final test-node-1))
         :test-nodes [test-node-1 test-node-2]}))))

(defn reference-health-check
  "Comprehensive health check for the reference system"
  []
  (merge (validate-reference-integrity)
         (reference-stats)
         {:operation-test (test-reference-operations)}))