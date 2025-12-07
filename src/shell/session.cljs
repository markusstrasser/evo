(ns shell.session
  "Ephemeral UI session state - separate from persistent document DB.

   Architecture:
   - Session (!session) holds UI state that TRIGGERS re-renders:
     selection, focus, folding, zoom, current-page, editing-block-id
   - Buffer (!buffer) holds high-velocity text that does NOT trigger re-renders:
     keystroke-by-keystroke text during editing

   Why two atoms?
   - Session changes trigger Replicant re-render (via watch in blocks_ui)
   - Buffer changes happen on every keystroke - re-rendering would be slow
   - Browser owns contenteditable DOM during editing; buffer is just a sync copy

   Session is intentionally NOT reactive (plain atom):
   - Replicant uses explicit render calls, not reactive subscriptions
   - UI updates are triggered by explicit (d/render!) calls
   - This keeps the mental model simple and predictable"
  (:require [clojure.set :as set]))

;; ── Session State Atom ────────────────────────────────────────────────────────
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
;;              :suppress-blur-exit false}       ; Prevent blur from exiting edit
;;  :sidebar   {:right []}}                      ; Right sidebar items
;;
;; NOTE: Buffer text is stored separately in !buffer (no render trigger)

(def default-session
  "Canonical initial session shape. Used by defonce and reset-session!."
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :ui {:folded #{}
        :zoom-root nil
        :current-page nil
        :editing-block-id nil
        :cursor-position nil
        :suppress-blur-exit false
        :doc-mode? false
        :drag nil}
   :sidebar {:right []}})

(defonce !session (atom default-session))

;; ── Buffer Atom (NO render watch) ─────────────────────────────────────────────
;; Separate atom for high-velocity keystroke storage.
;; Changes to this atom do NOT trigger re-renders.
;;
;; Structure: {block-id text-string}
;; Example: {"block-123" "Hello world"}

(defonce !buffer (atom {}))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn get-session
  "Get current session state (dereferenced).

   Example:
     (get-session)
     ;=> {:cursor {...} :selection {...} :buffer {...} :ui {...}}"
  []
  @!session)

(defn swap-session!
  "Update session state using a function.

   Example:
     (swap-session! assoc-in [:cursor :block-id] \"block-123\")
     (swap-session! update-in [:ui :folded] conj \"block-456\")"
  [f & args]
  (apply swap! !session f args))

(defn reset-session!
  "Reset session to fresh state.

   Used for testing and clearing all ephemeral state.
   Also clears the buffer atom."
  []
  (reset! !buffer {})
  (reset! !session default-session))

;; ── Query Helpers (Session equivalents of kernel.query) ──────────────────────

(defn editing-block-id
  "Get currently editing block ID from session.

   Session equivalent of (q/editing-block-id db)."
  []
  (get-in @!session [:ui :editing-block-id]))

(defn cursor-position
  "Get cursor position for enter-edit from session."
  []
  (get-in @!session [:ui :cursor-position]))

(defn folded
  "Get set of folded block IDs from session."
  []
  (get-in @!session [:ui :folded]))

(defn zoom-root
  "Get current zoom root from session."
  []
  (get-in @!session [:ui :zoom-root]))

(defn current-page
  "Get current active page from session."
  []
  (get-in @!session [:ui :current-page]))

(defn selection-nodes
  "Get set of selected block IDs from session."
  []
  (get-in @!session [:selection :nodes]))

(defn focus-id
  "Get focused block ID from session."
  []
  (get-in @!session [:selection :focus]))

;; ── Session Mutation API ─────────────────────────────────────────────────────

(defn set-cursor-position!
  "Set cursor position for enter-edit (number, :start, :end, :max).
   
   Used when entering edit mode or after undo/redo to restore cursor."
  [pos]
  (swap-session! assoc-in [:ui :cursor-position] pos))

(defn clear-cursor-position!
  "Clear pending cursor position after it's been applied to DOM."
  []
  (swap-session! assoc-in [:ui :cursor-position] nil))

(defn set-current-page!
  "Set the current active page."
  [page-id]
  (swap-session! assoc-in [:ui :current-page] page-id))

(defn clear-pending-selection!
  "Clear pending selection state."
  []
  (swap-session! assoc-in [:ui :pending-selection] nil))

(defn- deep-merge-session
  "Deep merge for session state with proper handling of:
   - Maps: recursively merge
   - Sets: union (not replace)
   - Scalars: new value wins
   
   This prevents clobbering of nested sets like :selection :nodes."
  [base updates]
  (cond
    ;; Both maps: recursive merge
    (and (map? base) (map? updates))
    (merge-with deep-merge-session base updates)

    ;; Both sets: union
    (and (set? base) (set? updates))
    (set/union base updates)

    ;; Otherwise: new value wins (including nil to clear)
    :else updates))

(defn merge-session-updates!
  "Merge session updates map into current session.
   
   Used by kernel.api and shell runtime for bulk session state updates.
   Uses deep-merge-session for proper handling of:
   - Nested maps (recursive merge)
   - Sets like :selection :nodes (union, not replace)
   - Scalars (new value overwrites)"
  [updates]
  (swap-session! #(deep-merge-session % updates)))

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

   Reads from the high-velocity !buffer atom (not !session).

   Example:
     (buffer-text \"block-123\")
     ;=> \"current typing text...\""
  [block-id]
  (get @!buffer block-id))

(defn suppress-blur-exit?
  "Check if blur should NOT exit edit mode.

   This is set during structural operations (indent, outdent, move)
   to prevent the blur handler from exiting edit mode when the DOM
   re-renders and the contenteditable temporarily loses focus."
  []
  (get-in @!session [:ui :suppress-blur-exit]))

(defn doc-mode?
  "Check if doc-mode is active (Enter/Shift+Enter swapped).

   LOGSEQ PARITY: In doc-mode, Enter inserts newline and Shift+Enter creates new block."
  []
  (get-in @!session [:ui :doc-mode?]))

;; ── Drag State API ────────────────────────────────────────────────────────────

(defn drag-start!
  "Start dragging block(s). If block is in selection, drags all selected.

   Drop zones (Logseq parity):
   - :above   - top 16px of block → place as sibling above
   - :nested  - right offset >50px → nest as child
   - :below   - default → place as sibling below"
  [block-ids]
  (swap-session! assoc-in [:ui :drag] {:dragging-ids (set block-ids)
                                       :drop-target nil}))

(defn drag-end!
  "Clear drag state."
  []
  (swap-session! assoc-in [:ui :drag] nil))

(defn drag-over!
  "Update drop target during drag.

   zone is one of :above, :nested, :below"
  [target-id zone]
  (swap-session! assoc-in [:ui :drag :drop-target] {:id target-id :zone zone}))

(defn dragging-ids
  "Get set of block IDs being dragged."
  []
  (get-in @!session [:ui :drag :dragging-ids]))

(defn drop-target
  "Get current drop target {:id :zone}."
  []
  (get-in @!session [:ui :drag :drop-target]))

(defn dragging?
  "Check if any drag is in progress."
  []
  (some? (get-in @!session [:ui :drag])))

(defn suppress-blur-exit!
  "Set flag to prevent blur from exiting edit mode during structural ops.

   The flag auto-clears after a delay to ensure it doesn't get stuck.
   200ms covers the re-render + blur cycle with margin for safety."
  []
  (swap-session! assoc-in [:ui :suppress-blur-exit] true)
  ;; Auto-clear after delay - 200ms covers re-render cycle with margin
  (js/setTimeout #(swap-session! assoc-in [:ui :suppress-blur-exit] false) 200))

;; ── Debug Helpers ─────────────────────────────────────────────────────────────

(defn dump-session
  "Pretty-print current session state for debugging.

   Example (from browser console):
     shell.session.dump_session()"
  []
  (js/console.log "Session state:")
  (js/console.log (clj->js @!session)))

(defn clear-session!
  "Clear all session state (for testing/debugging)."
  []
  (reset-session!)
  (js/console.log "Session cleared"))
