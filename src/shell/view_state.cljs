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
   - This keeps the mental model simple and predictable")

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
        :journals-view? false ; Show all journals stacked (newest first)
        :drag nil
        :sidebar-visible? true ; Left sidebar (pages) visibility
        :hotkeys-visible? false ; Hotkeys reference panel visibility
        :autocomplete nil ; Autocomplete popup state (see autocomplete-show!)
        :quick-switcher nil ; Quick switcher state {:query "" :selected-idx 0}
        :notification nil ; Toast notification {:message :type :action :timeout-id}
        :favorites #{} ; Set of favorited page IDs (star icon in sidebar)
        :recents []} ; Vector of recently visited page IDs (most recent first)
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

(defn journals-view?
  "Check if journals view is active (showing all journals stacked)."
  []
  (get-in @!view-state [:ui :journals-view?] false))

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
   - Sets like :selection :nodes (REPLACE, not union - handler computes final state)
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

(defn toggle-journals-view!
  "Toggle journals view (shows all journals stacked, newest first)."
  []
  (swap-view-state! update-in [:ui :journals-view?] not))

(defn set-journals-view!
  "Set journals view state explicitly."
  [active?]
  (swap-view-state! assoc-in [:ui :journals-view?] active?))

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

   The flag auto-clears after two animation frames to ensure it doesn't get stuck.
   Double-rAF guarantees we're past the render cycle (~32ms at 60Hz).

   LOGSEQ PARITY: Logseq uses single rAF for deferred focus. We use double-rAF
   for blur suppression because blur fires synchronously during DOM removal,
   before the new contenteditable mounts. Two frames ensures:
   1. First rAF: scheduled before paint
   2. Second rAF: after paint, new DOM is mounted and focused"
  []
  (swap-view-state! assoc-in [:ui :keep-edit-on-blur] true)
  ;; Double-rAF: first frame schedules, second frame clears after render
  (js/requestAnimationFrame
   #(js/requestAnimationFrame
     (fn [] (swap-view-state! assoc-in [:ui :keep-edit-on-blur] false)))))

;; ── Autocomplete API ──────────────────────────────────────────────────────────
;; Ephemeral state for autocomplete popups (page refs, block refs, commands, etc.)
;;
;; Structure when active:
;; {:type :page-ref        ; Source type (:page-ref, :block-ref, :command, :tag)
;;  :block-id "block-123"  ; Block being edited
;;  :trigger-pos 5         ; Cursor position where trigger started (after [[)
;;  :query "proj"          ; Current search text (typed after trigger)
;;  :selected 0            ; Index of selected item in results
;;  :items [...]}          ; Filtered results (cached for render)

(defn autocomplete
  "Get current autocomplete state, or nil if not active."
  []
  (get-in @!view-state [:ui :autocomplete]))

(defn autocomplete-active?
  "Check if autocomplete popup is currently active."
  []
  (some? (get-in @!view-state [:ui :autocomplete])))

(defn autocomplete-show!
  "Show autocomplete popup with initial state.

   type: Source type (:page-ref, :block-ref, :command, :tag)
   block-id: Block being edited
   trigger-pos: Cursor position where trigger started"
  [type block-id trigger-pos]
  (swap-view-state! assoc-in [:ui :autocomplete]
                    {:type type
                     :block-id block-id
                     :trigger-pos trigger-pos
                     :query ""
                     :selected 0
                     :items []}))

(defn autocomplete-update!
  "Update autocomplete state (query, items, selected).

   Example:
     (autocomplete-update! {:query \"proj\" :items [...]})"
  [updates]
  (swap-view-state! update-in [:ui :autocomplete] merge updates))

(defn autocomplete-dismiss!
  "Dismiss/hide autocomplete popup."
  []
  (swap-view-state! assoc-in [:ui :autocomplete] nil))

(defn autocomplete-navigate!
  "Navigate selection up or down in autocomplete list.

   direction: :up or :down"
  [direction]
  (let [{:keys [selected items]} (autocomplete)
        max-idx (max 0 (dec (count items)))
        new-idx (case direction
                  :up (max 0 (dec selected))
                  :down (min max-idx (inc selected))
                  selected)]
    (swap-view-state! assoc-in [:ui :autocomplete :selected] new-idx)))

;; ── Quick Switcher (Cmd+K) ────────────────────────────────────────────────────

(defn quick-switcher
  "Get current quick-switcher state.
   Returns nil if closed, or {:query \"...\" :selected-idx 0} if open."
  []
  (get-in @!view-state [:ui :quick-switcher]))

(defn quick-switcher-visible?
  "Check if quick switcher overlay is open."
  []
  (some? (quick-switcher)))

(defn quick-switcher-open!
  "Open the quick switcher overlay."
  []
  (swap-view-state! assoc-in [:ui :quick-switcher] {:query "" :selected-idx 0}))

(defn quick-switcher-close!
  "Close the quick switcher overlay."
  []
  (swap-view-state! assoc-in [:ui :quick-switcher] nil))

(defn quick-switcher-toggle!
  "Toggle quick switcher visibility. Bound to Cmd+K."
  []
  (if (quick-switcher-visible?)
    (quick-switcher-close!)
    (quick-switcher-open!)))

(defn quick-switcher-set-query!
  "Update the search query in quick switcher."
  [query]
  (swap-view-state! assoc-in [:ui :quick-switcher :query] query)
  ;; Reset selection to first result when query changes
  (swap-view-state! assoc-in [:ui :quick-switcher :selected-idx] 0))

(defn quick-switcher-navigate!
  "Navigate selection up or down in quick switcher results.
   direction: :up or :down
   result-count: total number of results (needed for bounds checking)"
  [direction result-count]
  (let [{:keys [selected-idx]} (quick-switcher)
        max-idx (max 0 (dec result-count))
        new-idx (case direction
                  :up (max 0 (dec selected-idx))
                  :down (min max-idx (inc selected-idx))
                  selected-idx)]
    (swap-view-state! assoc-in [:ui :quick-switcher :selected-idx] new-idx)))

;; ── Debug Helpers ─────────────────────────────────────────────────────────────

(defn dump-view-state
  "Pretty-print current view state for debugging.

   Example (from browser console):
     shell.view_state.dump_view_state()"
  []
  (js/console.log "View state:")
  (js/console.log (clj->js @!view-state)))

;; ── Clipboard State ──────────────────────────────────────────────────────────

(defn clipboard-blocks
  "Get internal clipboard blocks (preserves hierarchy from copy/cut).
   Returns vector of {:depth :text} maps, or nil if not set."
  []
  (get-in @!view-state [:ui :clipboard-blocks]))

(defn clipboard-text
  "Get clipboard text (markdown format for external paste)."
  []
  (get-in @!view-state [:ui :clipboard-text]))

(defn clear-clipboard!
  "Clear clipboard state."
  []
  (swap-view-state! update :ui dissoc :clipboard-blocks :clipboard-text))

(defn clear-view-state!
  "Clear all view state (for testing/debugging)."
  []
  (reset-view-state!)
  (js/console.log "View state cleared"))

;; ── Notification/Toast API ──────────────────────────────────────────────────
;; Toast notifications with optional undo action.
;;
;; Structure when active:
;; {:message "Deleted page"
;;  :type :success          ; :success, :error, :warning, :info
;;  :action {:label "Undo"  ; Optional action button
;;           :on-click fn}
;;  :timeout-id 123}        ; setTimeout ID for auto-dismiss

(defn notification
  "Get current notification state, or nil if not showing."
  []
  (get-in @!view-state [:ui :notification]))

(defn dismiss-notification!
  "Dismiss the current notification."
  []
  ;; Clear timeout if any
  (when-let [tid (get-in @!view-state [:ui :notification :timeout-id])]
    (js/clearTimeout tid))
  (swap-view-state! assoc-in [:ui :notification] nil))

(defn show-notification!
  "Show a toast notification.

   Args:
     message - Text to display
     opts - Optional map:
       :type    - :success (default), :error, :warning, :info
       :action  - {:label \"Undo\" :on-click fn} for action button
       :timeout - Auto-dismiss ms (default 5000, nil = no auto-dismiss)

   Example:
     (show-notification! \"Deleted page\" {:type :success
                                          :action {:label \"Undo\"
                                                   :on-click #(restore-page! id)}
                                          :timeout 5000})"
  ([message] (show-notification! message {}))
  ([message {:keys [action timeout]
             :or {timeout 5000}
             :as opts}]
   ;; Clear any existing timeout
   (when-let [existing (notification)]
     (when-let [tid (:timeout-id existing)]
       (js/clearTimeout tid)))

   ;; Set up auto-dismiss timeout
   (let [timeout-id (when timeout
                      (js/setTimeout dismiss-notification! timeout))
         notification-data (cond-> {:message message
                                    :type (or (:type opts) :success)}
                             action (assoc :action action)
                             timeout-id (assoc :timeout-id timeout-id))]
     (swap-view-state! assoc-in [:ui :notification] notification-data))))

;; ── LocalStorage Persistence ─────────────────────────────────────────────────
;; Persist favorites and recents to localStorage for cross-session continuity.

(def ^:const storage-key-favorites "evo:favorites")
(def ^:const storage-key-recents "evo:recents")
(def ^:const max-recents 15)

(defn- save-to-storage!
  "Save value to localStorage as JSON."
  [key value]
  (try
    (.setItem js/localStorage key (js/JSON.stringify (clj->js value)))
    (catch :default _e nil)))

(defn- load-from-storage
  "Load value from localStorage, parsing JSON."
  [key default]
  (try
    (if-let [stored (.getItem js/localStorage key)]
      (js->clj (js/JSON.parse stored) :keywordize-keys false)
      default)
    (catch :default _e default)))

(defn load-persisted-state!
  "Load favorites and recents from localStorage into view state.
   Call this once at app startup."
  []
  (let [favorites (set (load-from-storage storage-key-favorites []))
        recents (vec (load-from-storage storage-key-recents []))]
    (swap-view-state! assoc-in [:ui :favorites] favorites)
    (swap-view-state! assoc-in [:ui :recents] recents)))

;; ── Favorites API (Logseq-style star icon) ──────────────────────────────────
;; Favorites are page IDs that user explicitly starred.
;; Persisted to localStorage for cross-session continuity.

(defn favorites
  "Get set of favorited page IDs."
  []
  (get-in @!view-state [:ui :favorites] #{}))

(defn favorite?
  "Check if page is favorited."
  [page-id]
  (contains? (favorites) page-id))

(defn- persist-favorites!
  "Save current favorites to localStorage."
  []
  (save-to-storage! storage-key-favorites (vec (favorites))))

(defn toggle-favorite!
  "Toggle favorite status for a page. Returns new favorite status."
  [page-id]
  (let [currently-fav? (favorite? page-id)]
    (if currently-fav?
      (swap-view-state! update-in [:ui :favorites] disj page-id)
      (swap-view-state! update-in [:ui :favorites] conj page-id))
    (persist-favorites!)
    (not currently-fav?)))

(defn add-favorite!
  "Add page to favorites."
  [page-id]
  (swap-view-state! update-in [:ui :favorites] conj page-id)
  (persist-favorites!))

(defn remove-favorite!
  "Remove page from favorites."
  [page-id]
  (swap-view-state! update-in [:ui :favorites] disj page-id)
  (persist-favorites!))

;; ── Recents API (auto-populated on page visits) ─────────────────────────────
;; Recent pages are tracked automatically when user visits a page.
;; LOGSEQ PARITY: Clicking an existing recent does NOT move it to top.
;; Newest items appear at the END (appended), not front.
;; Capped at max-recents entries. Persisted to localStorage.

(defn recents
  "Get vector of recently visited page IDs."
  []
  (get-in @!view-state [:ui :recents] []))

(defn- persist-recents!
  "Save current recents to localStorage."
  []
  (save-to-storage! storage-key-recents (recents)))

(defn add-to-recents!
  "Add page to recent visits. Called when switching pages.
   LOGSEQ PARITY: If page already in list, position is NOT changed.
   New pages are appended to end. Caps at max-recents."
  [page-id]
  (when page-id
    (let [current-recents (recents)]
      ;; Only add if not already in list (Logseq behavior)
      (when-not (some #{page-id} current-recents)
        (swap-view-state! update-in [:ui :recents]
                          (fn [recents]
                            (->> (conj (vec recents) page-id)
                                 (take-last max-recents)
                                 vec)))
        (persist-recents!)))))

(defn clear-recents!
  "Clear all recent pages."
  []
  (swap-view-state! assoc-in [:ui :recents] [])
  (persist-recents!))

