(ns dev.data-diff
  "Enhanced data diffing and visualization using Dataspex.

   Features:
   - Tree view for DB state with expandable nodes
   - Proper data diffs between before/after states
   - Visual/DB mismatch detection
   - Human-readable state inspection"
  (:require [dataspex.hiccup :as hiccup]
            [dataspex.diff :as ddiff]
            [clojure.string :as str]))

;; ── Data Tree Rendering ────────────────────────────────────────────────────

(defn render-data-tree
  "Render data as an interactive tree using Dataspex rendering.
   Returns hiccup structure."
  [data & [opts]]
  (hiccup/render-dictionary data (merge {:inline? false} opts)))

(defn render-data-inline
  "Render data inline (compact) using Dataspex rendering."
  [data & [opts]]
  (hiccup/render-inline data opts))

;; ── Diff Computation ──────────────────────────────────────────────────────

(defn compute-diff
  "Compute diff between two data structures using Dataspex diff.
   Returns a map with:
   - :before - original data
   - :after - new data
   - :changes - list of changes with :path, :type (+ or -), :value"
  [before after]
  (let [changes (ddiff/diff before after)]
    {:before before
     :after after
     :changes (map (fn [[path op value]]
                     {:path path
                      :type op
                      :value value
                      :old-value (when (= op :-) value)
                      :new-value (when (= op :+) value)})
                   changes)}))

(defn has-changes?
  "Check if there are any changes between two data structures."
  [before after]
  (seq (ddiff/diff before after)))

;; ── DB State Comparison ────────────────────────────────────────────────────

(defn extract-relevant-state
  "Extract relevant parts of DB for comparison.
   Filters out internal/derived state to focus on user-visible data."
  [db]
  (select-keys db [:nodes :children-by-parent :roots]))

(defn compare-db-states
  "Compare two DB states and return structured diff.
   Focuses on user-visible changes."
  [db-before db-after]
  (let [state-before (extract-relevant-state db-before)
        state-after (extract-relevant-state db-after)]
    (compute-diff state-before state-after)))

;; ── Visual State Extraction ───────────────────────────────────────────────

(defn extract-visual-state
  "Extract what the user should see visually from DB.
   Returns a simplified representation of the visual tree."
  [db root-id]
  (letfn [(build-tree [id depth]
            (when-let [node (get-in db [:nodes id])]
              (let [children (get-in db [:children-by-parent id] [])]
                {:id id
                 :type (:type node)
                 :text (get-in node [:props :text] "")
                 :title (get-in node [:props :title] "")
                 :depth depth
                 :children (mapv #(build-tree % (inc depth)) children)})))]
    (build-tree root-id 0)))

(defn compare-visual-states
  "Compare visual representations of before/after DB states.
   Useful for detecting visual/DB mismatches."
  [db-before db-after root-id]
  (let [visual-before (extract-visual-state db-before root-id)
        visual-after (extract-visual-state db-after root-id)]
    (compute-diff visual-before visual-after)))

;; ── Mismatch Detection ─────────────────────────────────────────────────────

(defn detect-db-visual-mismatch
  "Detect if there's a mismatch between DB state and visual rendering.
   Returns nil if no mismatch, or a map describing the mismatch."
  [db expected-visual-state root-id]
  (let [actual-visual (extract-visual-state db root-id)
        mismatches []]
    ;; Simple mismatch detection - can be expanded
    (when (has-changes? expected-visual-state actual-visual)
      {:type :visual-db-mismatch
       :expected expected-visual-state
       :actual actual-visual
       :diff (compute-diff expected-visual-state actual-visual)})))

;; ── Formatting Utilities ───────────────────────────────────────────────────

(defn format-change
  "Format a single change from diff for human reading."
  [change]
  (case (:type change)
    :+ (str "  + Added: " (pr-str (:path change)) " → " (pr-str (:value change)))
    :- (str "  - Removed: " (pr-str (:path change)))
    :r (str "  * Changed: " (pr-str (:path change))
            " from " (pr-str (:old-value change))
            " to " (pr-str (:new-value change)))
    (str "  ? " (pr-str change))))

(defn format-changes-summary
  "Format all changes as human-readable summary."
  [changes]
  (if (empty? changes)
    "No changes"
    (str/join "\n" (map format-change changes))))

(defn format-diff-summary
  "Format diff result as human-readable summary."
  [{:keys [changes]}]
  (str "Changes (" (count changes) "):\n"
       (format-changes-summary changes)))

;; ── DOM State Helpers ──────────────────────────────────────────────────────

(defn extract-dom-state
  "Extract current DOM state for comparison (browser only).
   Returns a map representing the DOM tree structure."
  []
  (when-let [root (.querySelector js/document "[data-block-tree]")]
    (letfn [(extract-node [el]
              {:id (.-id el)
               :text (.-textContent el)
               :children (vec (map extract-node (array-seq (.-children el))))})]
      (extract-node root))))

(defn compare-db-with-dom
  "Compare DB state with actual DOM rendering.
   Useful for detecting rendering bugs."
  [db root-id]
  (let [db-visual (extract-visual-state db root-id)
        dom-state (extract-dom-state)]
    (when dom-state
      (compute-diff db-visual dom-state))))
