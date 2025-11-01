(ns components.block-ref
  "Block reference component for rendering transcluded blocks.")

(defn BlockRef
  "Render an embedded block reference.

   Props:
   - db: application database
   - block-id: ID of block to reference
   - ref-set: Set of block IDs in current reference chain (for cycle detection)

   Cycle Detection:
   - If block-id is in ref-set, we have a circular reference
   - Display a warning instead of infinite recursion

   Display Modes:
   - Normal: Shows referenced block text inline
   - Missing: Block ID doesn't exist - show error
   - Circular: Self/mutual reference detected - show warning"
  [{:keys [db block-id ref-set]}]
  (let [self-ref? (contains? (or ref-set #{}) block-id)
        block (get-in db [:nodes block-id])
        text (get-in block [:props :text] "")]
    (cond
      ;; Cycle detected - prevent infinite loop
      self-ref?
      [:span.block-ref.block-ref-circular
       {:title "Circular reference detected"
        :data-block-id block-id}
       "((circular))"]

      ;; Block not found
      (nil? block)
      [:span.block-ref.block-ref-missing
       {:title (str "Block not found: " block-id)
        :data-block-id block-id}
       "((" block-id " not found))"]

      ;; Normal reference - render block text
      :else
      [:span.block-ref
       {:title (str "Reference to block: " block-id)
        :data-block-id block-id}
       text])))
