(ns utils.intent-helpers
  "Shared utilities for intent handlers.

   Common patterns for building operations and session updates
   across multiple plugins (editing, context-editing, etc.).")

;; ── Block ID Generation ───────────────────────────────────────────────────────

(defn make-new-block-id
  "Generate a new block ID using random UUID."
  []
  (str "block-" (random-uuid)))

;; ── Session Updates ───────────────────────────────────────────────────────────

(defn make-cursor-update
  "Create cursor position update for session state.

   Args:
     block-id: Block to edit
     cursor-pos: Position within block text

   Returns:
     Session update map with :ui key"
  [block-id cursor-pos]
  {:ui {:editing-block-id block-id
        :cursor-position cursor-pos}})

;; ── Block Split Operations ────────────────────────────────────────────────────

(defn split-text-at
  "Split text at cursor position, returning [before after]."
  [text cursor-pos]
  [(subs text 0 cursor-pos) (subs text cursor-pos)])

(defn make-split-ops
  "Create standard split operations: update current, create new, place new.

   Args:
     block-id: Current block ID
     parent: Parent ID for placement
     before: Text for current block
     after: Text for new block
     new-id: ID for new block (or nil to generate)

   Returns:
     Vector of [ops new-block-id] for operation chaining."
  [block-id parent before after new-id]
  (let [new-id (or new-id (make-new-block-id))]
    [[{:op :update-node :id block-id :props {:text before}}
      {:op :create-node :id new-id :type :block :props {:text after}}
      {:op :place :id new-id :under parent :at {:after block-id}}]
     new-id]))

(defn make-split-result
  "Build standard split result with ops and session updates.

   Context map should contain:
     :block-id - Current block ID
     :parent - Parent block ID
     :new-id - New block ID (from make-new-block-id)

   Options map:
     :current-text - Update current block text (nil = no update)
     :new-text - Text for new block (required)
     :placement - Where to place new block (:after :before :first-child)
     :editing-block-id - Which block to edit after split
     :cursor-position - Cursor position in edited block

   Returns:
     Map with :ops and :session-updates keys"
  [{:keys [block-id parent new-id]}
   {:keys [current-text new-text placement editing-block-id cursor-position]}]
  (let [update-op (when current-text
                    [{:op :update-node :id block-id :props {:text current-text}}])
        create-op {:op :create-node :id new-id :type :block :props {:text new-text}}
        place-op {:op :place
                  :id new-id
                  :under (if (= placement :first-child) block-id parent)
                  :at (case placement
                        :after {:after block-id}
                        :before {:before block-id}
                        :first-child :first)}]
    {:ops (concat update-op [create-op place-op])
     :session-updates {:ui {:editing-block-id editing-block-id
                            :cursor-position cursor-position}}}))
