(ns shell.view-state
  "Ephemeral UI view state - separate from persistent document DB.

   Architecture:
   - View state (!view-state) holds UI state that TRIGGERS re-renders:
     selection, focus, folding, zoom, current-page, editing-block-id
   - Buffer (!buffer) holds high-velocity text that does NOT trigger re-renders:
     keystroke-by-keystroke text during editing

   Why two atoms?
   - View state changes trigger Replicant re-render (via watch in editor)
   - Buffer changes happen on every keystroke - re-rendering would be slow
   - Browser owns contenteditable DOM during editing; buffer is just a sync copy

   View state is intentionally NOT reactive (plain atom):
   - Replicant uses explicit render calls, not reactive subscriptions
   - UI updates are triggered by explicit (d/render!) calls
   - This keeps the mental model simple and predictable"
  (:require [clojure.set :as set]))

;; ── View State Atom ─────────────────────────────────────────────────────────
;; Holds UI state that TRIGGERS re-renders when changed.
;;
;; Structure:
;; {:cursor    {:block-id nil :offset 0}        ; Active caret position
;;  :selection {:nodes #{}                       ; Selected block IDs
;;              :focus nil                       ; Focus block ID
;;              :anchor nil}                     ; Anchor for range selection
;;  :ui        {:folded #{}                      ; Folded block IDs
;;              :zoom-root nil                   ; Current zoom root
;;              :current-page nil                ; Active page
;;              :editing-block-id nil            ; Block in edit mode
;;              :cursor-position nil             ; Cursor position for enter-edit
;;              :keep-edit-on-blur false}        ; Prevent blur from exiting edit
;;  :sidebar   {:right []}}                      ; Right sidebar items
;;
;; NOTE: Buffer text is stored separately in !buffer (no render trigger)

(def default-view-state
  "Canonical initial view-state shape. Used by defonce and reset-view-state!."
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :ui {:folded #{}
        :zoom-root nil
        :current-page nil
        :editing-block-id nil
        :cursor-position nil
        :keep-edit-on-blur false
        :document-view? false
        :drag nil
        :sidebar-visible? true      ; Left sidebar (pages) visibility
        :hotkeys-visible? false}    ; Hotkeys reference panel visibility
   :sidebar {:right []}})

(defonce !view-state (atom default-view-state))

;; ── Buffer Atom (NO render watch) ─────────────────────────────────────────────
;; Separate atom for high-velocity keystroke storage.
;; Changes to this atom do NOT trigger re-renders.
;;
;; Structure: {block-id text-string}
;; Example: {"block-123" "Hello world"}

(defonce !buffer (atom {}))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn get-view-state
  "Get current view state (dereferenced).

   Example:
     (get-view-state)
     ;=> {:cursor {...} :selection {...} :buffer {...} :ui {...}}"
  []
  @!view-state)

(defn swap-view-state!
  "Update view state using a function.

   Example:
     (swap-view-state! assoc-in [:cursor :block-id] \"block-123\")
     (swap-view-state! update-in [:ui :folded] conj \"block-456\")"
  [f & args]
  (apply swap! !view-state f args))

(defn reset-view-state!
  "Reset view state to fresh state.

   Used for testing and clearing all ephemeral state.
   Also clears the buffer atom."
  []
  (reset! !buffer {})
  (reset! !view-state default-view-state))

;; ── Query Helpers (View-state equivalents of kernel.query) ──────────────────────

(defn editing-block-id
  "Get currently editing block ID from view state.

   View-state equivalent of (q/editing-block-id db)."
  []
  (get-in @!view-state [:ui :editing-block-id]))

(defn cursor-position
  "Get cursor position for enter-edit from view state."
  []
  (get-in @!view-state [:ui :cursor-position]))

(defn folded
  "Get set of folded block IDs from view state."
  []
  (get-in @!view-state [:ui :folded]))

(defn zoom-root
  "Get current zoom root from view state."
  []
  (get-in @!view-state [:ui :zoom-root]))

(defn current-page
  "Get current active page from view state."
  []
  (get-in @!view-state [:ui :current-page]))

(defn selection-nodes
  "Get set of selected block IDs from view state."
  []
  (get-in @!view-state [:selection :nodes]))

(defn focus-id
  "Get focused block ID from view state."
  []
  (get-in @!view-state [:selection :focus]))

(defn sidebar-visible?
  "Check if the left sidebar (pages) is visible."
  []
  (get-in @!view-state [:ui :sidebar-visible?] true))

(defn hotkeys-visible?
  "Check if the hotkeys reference panel is visible."
  []
  (get-in @!view-state [:ui :hotkeys-visible?] false))

;; ── View State Mutation API ─────────────────────────────────────────────────────

(defn set-cursor-position!
  "Set cursor position for enter-edit (number, :start, :end, :max).

   Used when entering edit mode or after undo/redo to restore cursor."
  [pos]
  (swap-view-state! assoc-in [:ui :cursor-position] pos))

(defn clear-cursor-position!
  "Clear pending cursor position after it's been applied to DOM."
  []
  (swap-view-state! assoc-in [:ui :cursor-position] nil))

(defn set-current-page!
  "Set the current active page."
  [page-id]
  (swap-view-state! assoc-in [:ui :current-page] page-id))

(defn clear-pending-selection!
  "Clear pending selection state."
  []
  (swap-view-state! assoc-in [:ui :pending-selection] nil))

(defn- deep-merge-view-state
  "Deep merge for view state with proper handling of:
   - Maps: recursively merge
   - Sets: REPLACE (not union!) - selection handler computes final state
   - Scalars: new value wins

   IMPORTANT: Sets are REPLACED, not unioned. The selection handler
   in plugins.selection already calculates the correct final :nodes set
   (whether replacing, extending, or clearing). If we unioned here,
   {:selection :mode :replace :ids [\"b\"]} would add \"b\" to existing
   selection instead of replacing it."
  [base updates]
  (cond
    ;; Both maps: recursive merge
    (and (map? base) (map? updates))
    (merge-with deep-merge-view-state base updates)

    ;; Sets: REPLACE (handler computes correct final state)
    (and (set? base) (set? updates))
    updates

    ;; Otherwise: new value wins (including nil to clear)
    :else updates))

(defn merge-view-state-updates!
  "Merge view state updates map into current view state.

   Used by kernel.api and shell executor for bulk view state updates.
   Uses deep-merge-view-state for proper handling of:
   - Nested maps (recursive merge)
   - Sets like :selection :nodes (union, not replace)
   - Scalars (new value overwrites)"
  [updates]
  (swap-view-state! #(deep-merge-view-state % updates)))

;; ── Buffer API (high-velocity, no render trigger) ────────────────────────────

(defn buffer-set!
  "Store text in buffer for a block. Does NOT trigger re-render.

   Use this for keystroke-by-keystroke updates during editing.

   Example:
     (buffer-set! \"block-123\" \"Hello world\")"
  [block-id text]
  (swap! !buffer assoc block-id text))

(defn buffer-clear!
  "Clear buffer for a block (or all blocks if no id given).

   Call after committing text to DB.

   Example:
     (buffer-clear! \"block-123\")  ; clear one
     (buffer-clear!)               ; clear all"
  ([]
   (reset! !buffer {}))
  ([block-id]
   (swap! !buffer dissoc block-id)))

(defn buffer-text
  "Get buffer text for a specific block ID.

   Reads from the high-velocity !buffer atom (not !view-state).

   Example:
     (buffer-text \"block-123\")
     ;=> \"current typing text...\""
  [block-id]
  (get @!buffer block-id))

(defn keep-edit-on-blur?
  "Check if blur should NOT exit edit mode.

   This is set during structural operations (indent, outdent, move)
   to prevent the blur handler from exiting edit mode when the DOM
   re-renders and the contenteditable temporarily loses focus."
  []
  (get-in @!view-state [:ui :keep-edit-on-blur]))

(defn document-view?
  "Check if document-view is active (Enter/Shift+Enter swapped).

   LOGSEQ PARITY: In document-view, Enter inserts newline and Shift+Enter creates new block."
  []
  (get-in @!view-state [:ui :document-view?]))

(defn toggle-sidebar!
  "Toggle left sidebar visibility. Bound to Cmd+B."
  []
  (swap-view-state! update-in [:ui :sidebar-visible?] not))

(defn toggle-hotkeys!
  "Toggle hotkeys reference panel visibility. Bound to Cmd+?."
  []
  (swap-view-state! update-in [:ui :hotkeys-visible?] not))

;; ── Drag State API ────────────────────────────────────────────────────────────

(defn drag-start!
  "Start dragging block(s). If block is in selection, drags all selected.

   Drop zones (Logseq parity):
   - :above   - top 16px of block → place as sibling above
   - :nested  - right offset >50px → nest as child
   - :below   - default → place as sibling below"
  [block-ids]
  (swap-view-state! assoc-in [:ui :drag] {:dragging-ids (set block-ids)
                                          :drop-target nil}))

(defn drag-end!
  "Clear drag state."
  []
  (swap-view-state! assoc-in [:ui :drag] nil))

(defn drag-over!
  "Update drop target during drag.

   zone is one of :above, :nested, :below"
  [target-id zone]
  (swap-view-state! assoc-in [:ui :drag :drop-target] {:id target-id :zone zone}))

(defn dragging-ids
  "Get set of block IDs being dragged."
  []
  (get-in @!view-state [:ui :drag :dragging-ids]))

(defn drop-target
  "Get current drop target {:id :zone}."
  []
  (get-in @!view-state [:ui :drag :drop-target]))

(defn dragging?
  "Check if any drag is in progress."
  []
  (some? (get-in @!view-state [:ui :drag])))

(defn keep-edit-on-blur!
  "Set flag to prevent blur from exiting edit mode during structural ops.

   The flag auto-clears after a delay to ensure it doesn't get stuck.
   200ms covers the re-render + blur cycle with margin for safety."
  []
  (swap-view-state! assoc-in [:ui :keep-edit-on-blur] true)
  ;; Auto-clear after delay - 200ms covers re-render cycle with margin
  (js/setTimeout #(swap-view-state! assoc-in [:ui :keep-edit-on-blur] false) 200))

;; ── Debug Helpers ─────────────────────────────────────────────────────────────

(defn dump-view-state
  "Pretty-print current view state for debugging.

   Example (from browser console):
     shell.view_state.dump_view_state()"
  []
  (js/console.log "View state:")
  (js/console.log (clj->js @!view-state)))

(defn clear-view-state!
  "Clear all view state (for testing/debugging)."
  []
  (reset-view-state!)
  (js/console.log "View state cleared"))

