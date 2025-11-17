(ns diff-ops
  "Development tooling for converting editscript diffs to kernel operations.

   This enables REPL-driven prototyping by:
   1. Modeling desired state changes as pure data transformations
   2. Automatically generating kernel operations from the diff
   3. Verifying the kernel can express the desired change

   Usage:

     (require '[editscript.core :as e])
     (require '[diff-ops :as diff-ops])
     (require '[kernel.api :as api])

     ;; Model the desired change
     (def before-db {:nodes {\"b1\" {:type :block :props {:text \"Hello\"}}}})
     (def after-db {:nodes {\"b1\" {:type :block :props {:text \"World\"}}}})

     ;; Extract operations
     (def ops (diff-ops/diff->ops (e/diff before-db after-db)))
     ;; => [{:op :update-node :id \"b1\" :props {:text \"World\"}}]

     ;; Verify round-trip
     (def [result _] (api/transact before-db ops))
     (= after-db result) ;; => true

   IMPORTANT: This is for DEV/TEST only, not production runtime.
   The kernel's 3-op primitives remain the canonical API."
  (:require [editscript.core :as e]
            [clojure.string :as str]))

;; ── Edit Operation Interpretation ─────────────────────────────────────────────

(defn- interpret-node-add
  "Interpret an :+ (add) operation on a node.

   Path: [:nodes \"id\"]
   Value: {:type :block :props {...}}

   Returns: {:op :create-node :id \"id\" :type :block :props {...}}"
  [path value]
  (when (and (= 2 (count path))
             (= :nodes (first path))
             (string? (second path)))
    (let [node-id (second path)
          node-type (:type value)
          props (:props value)]
      {:op :create-node
       :id node-id
       :type node-type
       :props props})))

(defn- interpret-node-remove
  "Interpret a :- (remove) operation on a node.

   Path: [:nodes \"id\"]

   Returns: {:op :delete-node :id \"id\"}"
  [path]
  (when (and (= 2 (count path))
             (= :nodes (first path))
             (string? (second path)))
    (let [node-id (second path)]
      {:op :delete-node
       :id node-id})))

(defn- interpret-prop-replace
  "Interpret a :r (replace) operation on a node property.

   Path: [:nodes \"id\" :props :text]
   Value: \"new text\"

   Returns: {:op :update-node :id \"id\" :props {:text \"new text\"}}"
  [path value]
  (when (and (>= (count path) 3)
             (= :nodes (first path))
             (string? (second path))
             (= :props (nth path 2)))
    (let [node-id (second path)
          prop-path (vec (drop 3 path))
          props (if (seq prop-path)
                  (assoc-in {} prop-path value)
                  value)]
      {:op :update-node
       :id node-id
       :props props})))

(defn- interpret-children-replace
  "Interpret a :r (replace) operation on children-by-parent.

   Path: [:children-by-parent \"parent-id\"]
   Value: [\"child-1\" \"child-2\"]

   This is complex - we need to generate :place operations for all children.
   For now, return a comment explaining this needs manual handling."
  [path value]
  (when (and (= 2 (count path))
             (= :children-by-parent (first path)))
    (let [parent-id (second path)]
      ;; TODO: Generate :place operations for each child
      ;; This requires knowing the previous ordering and computing diffs
      {:op :comment
       :note (str "Children ordering changed for parent " parent-id
                  ". Generate :place operations manually.")
       :parent parent-id
       :new-order value})))

(defn- interpret-edit
  "Interpret a single editscript edit operation.

   Edit format: [[path] op-type value]
   where:
   - path: vector of keys into nested structure
   - op-type: :+ (add), :- (remove), or :r (replace)
   - value: new value (for :+ and :r)"
  [[path op-type value]]
  (case op-type
    :+ (interpret-node-add path value)
    :- (interpret-node-remove path)
    :r (or (interpret-prop-replace path value)
           (interpret-children-replace path value))
    nil))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn diff->ops
  "Convert an editscript diff to kernel operations.

   Takes an editscript diff object and returns a vector of kernel operations.

   Handles:
   - Node creation (:op :create-node)
   - Node deletion (:op :delete-node)
   - Property updates (:op :update-node)

   Does NOT handle:
   - Complex placement operations (requires more context)
   - Derived index changes (these are computed automatically)

   Returns: Vector of operation maps, or nil operations for unsupported edits.

   Example:

     (def before {:nodes {\"b1\" {:type :block :props {:text \"old\"}}}})
     (def after {:nodes {\"b1\" {:type :block :props {:text \"new\"}}}})
     (def diff (e/diff before after))
     (diff->ops diff)
     ;; => [{:op :update-node :id \"b1\" :props {:text \"new\"}}]"
  [diff]
  (->> (e/get-edits diff)
       (map interpret-edit)
       (filter some?)
       vec))

(defn visualize-diff
  "Pretty-print a diff for debugging.

   Shows each edit operation in a human-readable format."
  [diff]
  (doseq [[path op-type value] (e/get-edits diff)]
    (println (format "%-3s %s → %s"
                     (name op-type)
                     (str/join "/" (map str path))
                     (pr-str value)))))

(comment
  ;; Example usage in REPL

  (require '[editscript.core :as e])
  (require '[diff-ops :as diff-ops])

  ;; Simple property update
  (def before-db
    {:nodes {"b1" {:type :block :props {:text "Hello"}}}})

  (def after-db
    {:nodes {"b1" {:type :block :props {:text "World"}}}})

  (def diff (e/diff before-db after-db))

  (diff-ops/visualize-diff diff)
  ;; :r  nodes/b1/:props/:text → "World"

  (diff-ops/diff->ops diff)
  ;; => [{:op :update-node :id "b1" :props {:text "World"}}]

  ;; Node creation
  (def before-db
    {:nodes {"b1" {:type :block :props {:text "Hello"}}}})

  (def after-db
    {:nodes {"b1" {:type :block :props {:text "Hello"}}
             "b2" {:type :block :props {:text "New"}}}})

  (def diff (e/diff before-db after-db))

  (diff-ops/visualize-diff diff)
  ;; :+  nodes/b2 → {:type :block, :props {:text "New"}}

  (diff-ops/diff->ops diff)
  ;; => [{:op :create-node :id "b2" :type :block :props {:text "New"}}]

  ;; Node deletion
  (def before-db
    {:nodes {"b1" {:type :block :props {:text "Hello"}}
             "b2" {:type :block :props {:text "Bye"}}}})

  (def after-db
    {:nodes {"b1" {:type :block :props {:text "Hello"}}}})

  (def diff (e/diff before-db after-db))

  (diff-ops/visualize-diff diff)
  ;; :-  nodes/b2 → nil

  (diff-ops/diff->ops diff)
  ;; => [{:op :delete-node :id "b2"}]

  )
