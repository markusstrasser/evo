(ns agent.state-validation
  "State validation functions for keyboard operations and system health"
  (:require [shadow.cljs.devtools.api :as shadow]))

(defn validate-selection-state
  "Validate that selection state is consistent"
  []
  (cljs! "(let [store @evolver.core/store
                selected (:selected (:view store))
                nodes (:nodes store)]
            (cond
              (not (set? selected))
              {:error \"Selection is not a set\" :actual selected}

              (some #(not (contains? nodes %)) selected)
              {:error \"Selection contains non-existent nodes\"
               :invalid-nodes (filter #(not (contains? nodes %)) selected)}

              :else
              {:valid true :count (count selected) :nodes selected}))"))

(defn validate-node-structure
  "Validate that all nodes have required properties"
  []
  (cljs! "(let [nodes (:nodes @evolver.core/store)]
            (reduce (fn [acc [id node]]
                      (if (and (:type node) (:props node))
                        acc
                        (conj acc {:node-id id :missing (cond-> []
                                                              (not (:type node)) (conj :type)
                                                              (not (:props node)) (conj :props)))})))
                    []
                    nodes))"))

(defn validate-references
  "Validate that all references point to existing nodes"
  []
  (cljs! "(let [store @evolver.core/store
                nodes (:nodes store)
                references (:references store)]
            (reduce (fn [acc [from to]]
                      (cond
                        (not (contains? nodes from))
                        (conj acc {:error :missing-from-node :from from :to to})

                        (not (contains? nodes to))
                        (conj acc {:error :missing-to-node :from from :to to})

                        :else acc))
                    []
                    references))"))

(defn validate-keyboard-prerequisites
  "Validate prerequisites for keyboard operations"
  [operation]
  (case operation
    :delete
    (cljs! "(let [selected (:selected (:view @evolver.core/store))]
              (if (empty? selected)
                {:error \"No nodes selected for delete operation\"}
                {:valid true :selected-count (count selected)}))")

    :create-child
    (cljs! "(let [selected (:selected (:view @evolver.core/store))]
              (if (not= (count selected) 1)
                {:error \"Must have exactly one node selected for create-child\"}
                {:valid true :parent-id (first selected)}))")

    :create-sibling
    (cljs! "(let [selected (:selected (:view @evolver.core/store))]
              (if (not= (count selected) 1)
                {:error \"Must have exactly one node selected for create-sibling\"}
                {:valid true :sibling-id (first selected)}))")

    :add-reference
    (cljs! "(let [selected (:selected (:view @evolver.core/store))]
              (if (not= (count selected) 2)
                {:error \"Must have exactly two nodes selected for add-reference\"}
                {:valid true :nodes selected}))")

    :remove-reference
    (cljs! "(let [selected (:selected (:view @evolver.core/store))]
              (if (not= (count selected) 2)
                {:error \"Must have exactly two nodes selected for remove-reference\"}
                {:valid true :nodes selected}))")

    {:error (str "Unknown operation: " operation)}))

(defn validate-undo-redo-state
  "Validate undo/redo system state"
  []
  (cljs! "(let [store @evolver.core/store]
            {:has-undo? (seq (:undo-stack store))
             :has-redo? (seq (:redo-stack store))
             :undo-count (count (:undo-stack store))
             :redo-count (count (:redo-stack store))})"))

(defn comprehensive-health-check
  "Run all validation checks"
  []
  (println "\n🏥 COMPREHENSIVE SYSTEM HEALTH CHECK")
  (println "─────────────────────────────────────")

  (println "\n1. Selection State:")
  (validate-selection-state)

  (println "\n2. Node Structure:")
  (validate-node-structure)

  (println "\n3. References:")
  (validate-references)

  (println "\n4. Undo/Redo State:")
  (validate-undo-redo-state)

  (println "\n5. Keyboard Prerequisites:")
  (doseq [op [:delete :create-child :create-sibling :add-reference :remove-reference]]
    (println (str "  " (name op) ":"))
    (validate-keyboard-prerequisites op))

  (println "\n✅ Health check completed"))