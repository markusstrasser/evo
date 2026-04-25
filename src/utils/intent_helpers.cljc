(ns utils.intent-helpers
  "Shared utilities for intent handlers.

   Common patterns for building operations and session updates
   across multiple plugins (editing, context-editing, etc.)."
  (:require [utils.session-patch :as session-patch]))

;; ── Session Update Composition ───────────────────────────────────────────────

(defn merge-session-updates
  "Merge session update fragments without dropping nested sibling keys.

   Session updates usually touch a small number of top-level sections such as
   :ui, :selection, :sidebar, or :storage. A shallow merge loses data when two
   fragments both target :ui. Recursively merge maps, with non-map leaves
   remaining last-write-wins."
  [& updates]
  (apply session-patch/merge-patches updates))

;; ── Block ID Generation ───────────────────────────────────────────────────────

(defn make-new-block-id
  "Generate a new block ID using random UUID."
  []
  (str "block-" (random-uuid)))

;; ── Session Updates ───────────────────────────────────────────────────────────

(defn clear-selection-update
  "Create the canonical empty selection state."
  []
  {:selection {:nodes #{}
               :focus nil
               :anchor nil
               :direction nil}})

(defn select-only-update
  "Create a single-block selection update.

   If block-id is nil, returns the canonical empty selection."
  [block-id]
  (if block-id
    {:selection {:nodes #{block-id}
                 :focus block-id
                 :anchor block-id
                 :direction nil}}
    (clear-selection-update)))

(defn exit-edit-update
  "Clear editing state and cursor position."
  []
  {:ui {:editing-block-id nil
        :cursor-position nil}})

(defn cursor-position-update
  "Update cursor position without restating edit mode."
  [cursor-pos]
  {:ui {:cursor-position cursor-pos}})

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

(defn enter-edit-update
  "Enter edit mode for a block.

   Options:
   - :clear-selection? (default true) clears block selection first."
  ([block-id cursor-pos]
   (enter-edit-update block-id cursor-pos {}))
  ([block-id cursor-pos {:keys [clear-selection?]
                         :or {clear-selection? true}}]
   (merge-session-updates
    (when clear-selection? (clear-selection-update))
    (make-cursor-update block-id cursor-pos))))

(defn navigate-with-cursor-memory-intent
  "Build the canonical vertical navigation intent payload."
  ([{:keys [block-id current-block-id text current-text cursor-pos current-cursor-pos direction dom-adjacent-id]}]
   (cond-> {:type :navigate-with-cursor-memory
            :current-block-id (or current-block-id block-id)
            :current-text (or current-text text "")
            :current-cursor-pos (or current-cursor-pos cursor-pos 0)
            :direction direction}
     dom-adjacent-id (assoc :dom-adjacent-id dom-adjacent-id)))
  ([direction block-id current-text current-cursor-pos]
   (navigate-with-cursor-memory-intent {:direction direction
                                        :block-id block-id
                                        :current-text current-text
                                        :current-cursor-pos current-cursor-pos}))
  ([direction block-id current-text current-cursor-pos dom-adjacent-id]
   (navigate-with-cursor-memory-intent {:direction direction
                                        :block-id block-id
                                        :current-text current-text
                                        :current-cursor-pos current-cursor-pos
                                        :dom-adjacent-id dom-adjacent-id})))

(defn navigate-to-adjacent-intent
  "Build the canonical horizontal-boundary navigation intent payload."
  ([{:keys [block-id current-block-id direction cursor-position]}]
   {:type :navigate-to-adjacent
    :direction direction
    :current-block-id (or current-block-id block-id)
    :cursor-position cursor-position})
  ([direction block-id cursor-position]
   (navigate-to-adjacent-intent {:direction direction
                                 :block-id block-id
                                 :cursor-position cursor-position})))

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
