(ns macros.editing
  "Compositional editing macros.

   These macros handle multi-step editing operations where step N depends on
   the result of step N-1.

   Examples:
   - Smart Backspace: Delete block, then select previous block
   - Paste Lines: Create multiple blocks, then navigate to first
   - Insert Block: Create + place + focus in one action

   All macros return operations for atomic commit via tx/interpret."
  (:require [macros.script :as script]
            [kernel.query :as q]))

;; ── Smart Backspace ────────────────────────────────────────────────────────────

(defn smart-backspace
  "Delete empty block and navigate to previous block's end.

   Use Case:
   User presses backspace on empty block. We want:
   1. Delete the current block (move to trash)
   2. Select the previous block (or parent if no prev)
   3. Move cursor to end of selected block

   Why Macro:
   Step 2 needs to query :prev-id-of AFTER the block is deleted,
   because the derived indexes change after deletion.

   Args:
     db: Current database
     opts: Map with :id (block to delete)

   Returns:
     Vector of ops for atomic commit

   Example:
     (def ops (smart-backspace db {:id \"block-123\"}))
     (tx/interpret db ops)  ; Apply all ops atomically"
  [db {:keys [id]}]
  (:ops
    (script/run db
      [;; Step 1: Delete the block (move to trash)
       {:type :delete :id id}

       ;; Step 2: Query which block to select next
       ;; This function sees the DB AFTER deletion
       (fn [db-after-delete]
         ;; Try to find previous sibling, fall back to parent
         (when-let [target-id (or (q/prev-sibling db-after-delete id)
                                  (q/parent-of db-after-delete id))]
           ;; Return intents for selection + cursor positioning
           [{:type :select :ids [target-id]}
            ;; TODO: Add cursor positioning intent when available
            ;; {:type :cursor-move :id target-id :where :end}
            ]))])))

;; ── Paste Multi-Line ───────────────────────────────────────────────────────────

(defn paste-lines
  "Paste multiple lines as separate blocks.

   Use Case:
   User pastes text with newlines. We want:
   1. Split text into lines (pre-processing)
   2. Create one block per line
   3. Place all blocks in document
   4. Navigate to first new block

   Why Macro:
   Step 4 needs to know which blocks were created in steps 2-3.
   We generate IDs upfront so step 4 can reference them.

   Args:
     db: Current database
     opts: Map with:
       :text - Multi-line text to paste
       :under - Parent ID to place blocks under
       :at - Position anchor (:first, :last, {:after \"id\"}, etc.)

   Returns:
     Vector of ops for atomic commit

   Example:
     (def ops (paste-lines db {:text \"Line 1\\nLine 2\\nLine 3\"
                               :under \"parent-123\"
                               :at :last}))
     (tx/interpret db ops)"
  [db {:keys [text under at]}]
  (let [lines (remove empty? (clojure.string/split-lines text))
        ;; Generate IDs upfront (before macro runs)
        new-ids (repeatedly (count lines) #(str (random-uuid)))]

    (:ops
      (script/run db
        [;; Step 1: Create all blocks (static, can be done upfront)
         (mapv (fn [line-text id]
                 {:op :create-node
                  :id id
                  :type :block
                  :props {:text line-text}})
               lines
               new-ids)

         ;; Step 2: Place all blocks
         ;; Note: All blocks go to same parent/anchor
         ;; Later blocks push earlier ones forward
         (mapv (fn [id]
                 {:op :place
                  :id id
                  :under under
                  :at at})
               new-ids)

         ;; Step 3: Navigate to first new block
         (fn [_db-after-place]
           (when-let [first-id (first new-ids)]
             [{:type :select :ids [first-id]}
              ;; TODO: Add cursor positioning when available
              ;; {:type :cursor-move :id first-id :where :end}
              ]))]))))

;; ── Insert Block (Create + Focus) ─────────────────────────────────────────────

(defn insert-block
  "Create a new block and immediately focus it for editing.

   Use Case:
   User presses Enter to create new block. We want:
   1. Create new block
   2. Place it in document
   3. Select it
   4. Move cursor to start for typing

   Why Macro:
   Steps 3-4 need the new block to exist in the DB.
   We generate the ID upfront so all steps can reference it.

   Args:
     db: Current database
     opts: Map with:
       :under - Parent ID
       :at - Position anchor
       :text - Initial text (optional, default: \"\")

   Returns:
     Vector of ops for atomic commit

   Example:
     (def ops (insert-block db {:under \"parent-123\"
                                :at {:after \"block-456\"}
                                :text \"\"}))
     (tx/interpret db ops)"
  [db {:keys [under at text]}]
  (let [new-id (str (random-uuid))
        initial-text (or text "")]

    (:ops
      (script/run db
        [;; Step 1: Create block
         {:op :create-node
          :id new-id
          :type :block
          :props {:text initial-text}}

         ;; Step 2: Place block
         {:op :place
          :id new-id
          :under under
          :at at}

         ;; Step 3: Focus new block for editing
         (fn [_db-after-create]
           [{:type :select :ids [new-id]}
            ;; TODO: Add editing mode + cursor positioning
            ;; {:type :start-editing :id new-id}
            ;; {:type :cursor-move :id new-id :where :start}
            ])]))))
