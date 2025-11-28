(ns shell.session
  "Ephemeral UI session state - separate from persistent document DB.

   Phase 2 of kernel-session split refactor.

   Architecture:
   - Session holds ALL ephemeral UI state (cursor, selection, buffer, fold, zoom)
   - Components READ from session (not DB)
   - Components still WRITE to DB (for backward compatibility during migration)
   - Phase 3+ will make writes session-only

   Session is intentionally NOT reactive (plain atom):
   - Replicant uses explicit render calls, not reactive subscriptions
   - UI updates are triggered by explicit (d/render!) calls
   - This keeps the mental model simple and predictable")

;; ── Session State Atom ────────────────────────────────────────────────────────
;; Single atom holding all ephemeral UI state.
;;
;; Structure:
;; {:cursor    {:block-id nil :offset 0}        ; Active caret position
;;  :selection {:nodes #{}                       ; Selected block IDs
;;              :focus nil                       ; Focus block ID
;;              :anchor nil}                     ; Anchor for range selection
;;  :buffer    {:block-id nil                    ; Block being edited
;;              :text ""                         ; Current buffer text
;;              :dirty? false}                   ; Has uncommitted changes?
;;  :ui        {:folded #{}                      ; Folded block IDs
;;              :zoom-root nil                   ; Current zoom root
;;              :current-page nil                ; Active page
;;              :editing-block-id nil            ; Block in edit mode
;;              :cursor-position nil             ; Cursor position for enter-edit
;;              :suppress-blur-exit false}       ; Prevent blur from exiting edit (during structural ops)
;;  :sidebar   {:right []}}                      ; Right sidebar items

(defonce !session
  (atom
   {:cursor    {:block-id nil :offset 0}
    :selection {:nodes #{} :focus nil :anchor nil}
    :buffer    {:block-id nil :text "" :dirty? false}
    :ui        {:folded #{}
                :zoom-root nil
                :current-page nil
                :editing-block-id nil
                :cursor-position nil
                :suppress-blur-exit false}
    :sidebar   {:right []}}))

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

   Used for testing and clearing all ephemeral state."
  []
  (reset! !session
          {:cursor    {:block-id nil :offset 0}
           :selection {:nodes #{} :focus nil :anchor nil}
           :buffer    {:block-id nil :text "" :dirty? false}
           :ui        {:folded #{}
                       :zoom-root nil
                       :current-page nil
                       :editing-block-id nil
                       :cursor-position nil}
           :sidebar   {:right []}}))

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

(defn buffer-text
  "Get buffer text for a specific block ID.

   Example:
     (buffer-text \"block-123\")
     ;=> \"current typing text...\""
  [block-id]
  ;; Use string block-id consistently (not keyword) to match DB convention
  (get-in @!session [:buffer block-id]))

(defn buffer-dirty?
  "Check if buffer has uncommitted changes."
  []
  (get-in @!session [:buffer :dirty?]))

(defn suppress-blur-exit?
  "Check if blur should NOT exit edit mode.

   This is set during structural operations (indent, outdent, move)
   to prevent the blur handler from exiting edit mode when the DOM
   re-renders and the contenteditable temporarily loses focus."
  []
  (get-in @!session [:ui :suppress-blur-exit]))

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
