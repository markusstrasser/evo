(ns components.block-embed
  "Block embed component for rendering full block trees (with children).

   Similar to block references but shows the entire block structure,
   not just inline text.")

(defn BlockEmbed
  "Render an embedded block with its full tree structure.

   Props:
   - db: application database
   - block-id: ID of block to embed
   - embed-set: Set of block IDs in current embed chain (for cycle detection)
   - depth: Current embed depth (for limiting recursion)
   - max-depth: Maximum embed depth allowed (default 3)
   - Block: Block component reference (passed to avoid circular dependency)
   - on-intent: Intent dispatch callback

   Cycle Detection:
   - If block-id is in embed-set, we have a circular embed
   - Display a warning instead of infinite recursion

   Depth Limiting:
   - Prevents deeply nested embeds that could hurt performance
   - Default max-depth is 3 levels

   Display Modes:
   - Normal: Shows full block tree with children
   - Missing: Block ID doesn't exist - show error
   - Circular: Self/mutual reference detected - show warning
   - Max Depth: Too deeply nested - show warning"
  [{:keys [db block-id embed-set depth max-depth Block on-intent]
    :or {depth 0 max-depth 3}}]
  (let [self-embed? (contains? (or embed-set #{}) block-id)
        at-max-depth? (>= depth max-depth)
        block (get-in db [:nodes block-id])]
    (cond
      ;; Cycle detected - prevent infinite loop
      self-embed?
      [:div.block-embed.block-embed-circular
       {:style {:border "1px dashed rgb(255, 107, 107)"
                :padding "8px"
                :margin "4px 0"
                :background-color "rgb(255, 245, 245)"
                :border-radius "4px"}
        :title "Circular embed detected"
        :data-block-id block-id}
       "⚠️ Circular embed: (((" block-id ")))"]

      ;; Max depth reached
      at-max-depth?
      [:div.block-embed.block-embed-max-depth
       {:style {:border "1px dashed rgb(255, 169, 77)"
                :padding "8px"
                :margin "4px 0"
                :background-color "rgb(255, 249, 219)"
                :border-radius "4px"}
        :title "Maximum embed depth reached"
        :data-block-id block-id}
       "⚠️ Max embed depth reached: (((" block-id ")))"]

      ;; Block not found
      (nil? block)
      [:div.block-embed.block-embed-missing
       {:style {:border "1px dashed rgb(134, 142, 150)"
                :padding "8px"
                :margin "4px 0"
                :background-color "rgb(241, 243, 245)"
                :border-radius "4px"}
        :title (str "Block not found: " block-id)
        :data-block-id block-id}
       "❌ Block not found: (((" block-id ")))"]

      ;; Normal embed - render full block tree
      :else
      [:div.block-embed
       {:style {:border "1px solid rgb(233, 236, 239)"
                :padding "8px"
                :margin "4px 0"
                :background-color "rgb(248, 249, 250)"
                :border-radius "4px"}
        :title (str "Embedded block: " block-id)
        :data-block-id block-id}
       ;; Render the full block tree using the Block component
       ;; Pass updated embed-set to track the chain
       (when Block
         (Block {:db db
                 :block-id block-id
                 :depth 0  ; Reset depth for the embedded tree
                 :embed-set (conj (or embed-set #{}) block-id)
                 :embed-depth (inc depth)
                 :on-intent on-intent}))])))
