(ns scripts.editing
  "Compositional editing scripts.

   These scripts handle multi-step editing operations where step N depends on
   the result of step N-1.

   Examples:
   - Smart Backspace: Delete block, then report the next structural target
   - Paste Lines: Create multiple blocks, then report the first new ID
   - Insert Block: Create + place, then report the new block ID

   Scripts stay structural. They return ops plus structural facts that the
   enclosing handler can use to compute session updates."
  (:require [clojure.string :as str]
            [scripts.script :as script]
            [kernel.constants :as const]
            [kernel.query :as q]))

;; ── Operation Constructors ─────────────────────────────────────────────────────

(defn create-block-op
  "Build a :create-node operation for a block.

   Args:
     id: Block ID
     text: Block text content

   Returns:
     {:op :create-node ...} map"
  [id text]
  {:op :create-node
   :id id
   :type :block
   :props {:text text}})

(defn place-op
  "Build a :place operation.

   Args:
     id: Block ID to place
     under: Parent ID
     at: Position anchor (:first, :last, {:after id}, etc.)

   Returns:
     {:op :place ...} map"
  [id under at]
  {:op :place
   :id id
   :under under
   :at at})

(defn delete-block-op
  "Build the structural delete op used by scripts.

   Scripts do not compile the :delete intent because that would pull session
   behavior into scratch execution. They emit the underlying structural op and
   let the caller decide selection/editing updates."
  [id]
  {:op :place
   :id id
   :under const/root-trash
   :at :last})

;; ── Smart Backspace ────────────────────────────────────────────────────────────

(defn smart-backspace
  "Delete empty block and report which structural target should receive focus.

   Use Case:
   User presses backspace on empty block. We want:
   1. Delete the current block (move to trash)
   2. Report the previous block (or parent if no prev) so the caller can focus it

   Why Macro:
   The delete still runs through scratch execution so callers get one atomic
   structural change bundle.

   Args:
     db: Current database
     opts: Map with :id (block to delete)

   Returns:
     {:ops [...] :target-id ...}

   Example:
     (def result (smart-backspace db {:id \"block-123\"}))
     (tx/interpret db (:ops result))  ; Apply structural ops atomically
     (:target-id result)              ; Caller decides session updates"
  [db {:keys [id]}]
  (let [parent-id (q/parent-of db id)
        target-id (or (q/prev-sibling db id)
                      (when (string? parent-id) parent-id))
        result (script/run db
                           [;; Structural delete only; outer handler owns focus/selection.
                            (delete-block-op id)])]
    {:ops (:ops result)
     :target-id target-id}))

;; ── Paste Multi-Line ───────────────────────────────────────────────────────────

(defn paste-lines
  "Paste multiple lines as separate blocks.

   Use Case:
   User pastes text with newlines. We want:
   1. Split text into lines (pre-processing)
   2. Create one block per line
   3. Place all blocks in document
   4. Report the first new block so the caller can focus it

   Why Macro:
   The script stages create/place ops together and returns the generated IDs so
   session changes can be handled outside scratch execution.

   Args:
     db: Current database
     opts: Map with:
       :text - Multi-line text to paste
       :under - Parent ID to place blocks under
       :at - Position anchor (:first, :last, {:after \"id\"}, etc.)

   Returns:
     {:ops [...] :new-ids [...] :first-id ...}

   Example:
     (def result (paste-lines db {:text \"Line 1\\nLine 2\\nLine 3\"
                                  :under \"parent-123\"
                                  :at :last}))
     (tx/interpret db (:ops result))
     (:first-id result)"
  [db {:keys [text under at]}]
  (let [lines (remove empty? (str/split-lines text))
        ;; Generate IDs upfront (before macro runs)
        new-ids (repeatedly (count lines) #(str (random-uuid)))]
    {:ops (:ops (script/run db
                            [;; Step 1: Create all blocks (static, can be done upfront)
                             (mapv create-block-op new-ids lines)

                             ;; Step 2: Place all blocks
                             ;; Note: All blocks go to same parent/anchor.
                             ;; Later blocks push earlier ones forward.
                             (mapv #(place-op % under at) new-ids)]))
     :new-ids (vec new-ids)
     :first-id (first new-ids)}))

;; ── Insert Block (Create + Focus) ─────────────────────────────────────────────

(defn insert-block
  "Create a new block and report the new block ID for outer focus handling.

   Use Case:
   User presses Enter to create new block. We want:
   1. Create new block
   2. Place it in document
   3. Return the new block ID so the caller can enter edit mode

   Why Macro:
   We generate the ID upfront so structural ops and follow-up session logic can
   reference the same block.

   Args:
     db: Current database
     opts: Map with:
       :under - Parent ID
       :at - Position anchor
       :text - Initial text (optional, default: \"\")

   Returns:
     {:ops [...] :new-id ...}

   Example:
     (def result (insert-block db {:under \"parent-123\"
                                   :at {:after \"block-456\"}
                                   :text \"\"}))
     (tx/interpret db (:ops result))
     (:new-id result)"
  [db {:keys [under at text]}]
  (let [new-id (str (random-uuid))
        initial-text (or text "")]
    {:ops (:ops (script/run db
                            [;; Step 1: Create block
                             (create-block-op new-id initial-text)

                             ;; Step 2: Place block
                             (place-op new-id under at)]))
     :new-id new-id}))
