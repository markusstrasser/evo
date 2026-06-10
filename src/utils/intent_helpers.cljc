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
  ([{:keys [block-id current-block-id text current-text cursor-pos current-cursor-pos direction]}]
   {:type :navigate-with-cursor-memory
    :current-block-id (or current-block-id block-id)
    :current-text (or current-text text "")
    :current-cursor-pos (or current-cursor-pos cursor-pos 0)
    :direction direction})
  ([direction block-id current-text current-cursor-pos]
   (navigate-with-cursor-memory-intent {:direction direction
                                        :block-id block-id
                                        :current-text current-text
                                        :current-cursor-pos current-cursor-pos})))

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

