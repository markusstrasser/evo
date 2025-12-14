(ns utils.cursor-boundaries
  "Cursor boundary detection for contenteditable elements.

   Consolidates scattered boundary detection from components/block.cljs into
   a single source of truth. Computes all boundaries in ONE DOM read per keydown.

   Key design decisions:
   - Pure data output (map of booleans) - testable, loggable
   - IME composition tracking at document level - CJK/emoji input safety
   - Called ONCE per keydown, results passed to all handlers

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │  keydown event                                              │
   │       ↓                                                     │
   │  (boundary-state element) → {:at-start? :at-end? ...}      │
   │       ↓                                                     │
   │  IME guard: skip if composing                               │
   │       ↓                                                     │
   │  handlers receive boundaries as pure data                   │
   └─────────────────────────────────────────────────────────────┘"
)

;; ── IME Composition Tracking ──────────────────────────────────────────────────
;;
;; CJK input methods (Chinese, Japanese, Korean), emoji pickers, and dictation
;; all use IME composition. During composition, we MUST NOT intercept keyboard
;; events or we'll corrupt the user's input.
;;
;; The isComposing property on KeyboardEvent is unreliable in some browsers,
;; so we track composition state via document-level event listeners.

(defonce !composing? (atom false))

(defn- on-composition-start [_e]
  (reset! !composing? true))

(defn- on-composition-end [_e]
  (reset! !composing? false))

(defn setup-composition-tracking!
  "Initialize document-level IME composition tracking.
   Call once on app startup (e.g., in shell.core/init!)."
  []
  (.addEventListener js/document "compositionstart" on-composition-start)
  (.addEventListener js/document "compositionend" on-composition-end))

(defn teardown-composition-tracking!
  "Remove composition listeners. Call on app teardown if needed."
  []
  (.removeEventListener js/document "compositionstart" on-composition-start)
  (.removeEventListener js/document "compositionend" on-composition-end))

(defn composing?
  "Check if IME composition is currently active."
  []
  @!composing?)

;; ── Cursor Row Detection ──────────────────────────────────────────────────────
;;
;; Determines if cursor is on first/last visual row of a multi-line block.
;; Used for ArrowUp/ArrowDown to decide whether to navigate between blocks
;; or let browser handle intra-block cursor movement.

(defn- detect-row-position
  "Detect if cursor is on first/last visual row using Range bounding rect.

   Returns {:first-row? bool :last-row? bool} or nil if detection fails.

   Algorithm:
   - Get cursor rect via Range API
   - Compare cursor-top to element-top (first row check)
   - Compare cursor-top to element-bottom (last row check)
   - Use line-height heuristic (range height or fallback 20px)"
  [element]
  (when element
    (when-let [selection (.getSelection js/window)]
      (when (pos? (.-rangeCount selection))
        (let [range (.getRangeAt selection 0)
              rect (.getBoundingClientRect range)
              elem-rect (.getBoundingClientRect element)
              cursor-top (.-top rect)
              elem-top (.-top elem-rect)
              elem-bottom (.-bottom elem-rect)
              ;; Range height can be 0 for collapsed cursor - must check explicitly
              ;; (ClojureScript: 0 is truthy so (or 0 20) returns 0)
              raw-height (.-height rect)
              line-height (if (and raw-height (pos? raw-height)) raw-height 20)]
          {:first-row? (< (- cursor-top elem-top) line-height)
           :last-row? (< (- elem-bottom cursor-top) (* 1.5 line-height))})))))

;; ── Horizontal Boundary Detection ─────────────────────────────────────────────
;;
;; Determines if cursor is at start/end of text content.
;; Used for ArrowLeft/ArrowRight to decide whether to navigate between blocks.

(defn- detect-at-start?
  "Detect if cursor is at the very start of contenteditable.

   Handles edge cases:
   - Empty block (anchorNode === element itself)
   - First text node (anchorNode === firstChild)
   - Complex DOM (measure text before cursor via Range)

   Returns boolean."
  [element]
  (when-let [selection (.getSelection js/window)]
    (let [anchor-offset (.-anchorOffset selection)
          anchor-node (.-anchorNode selection)
          first-child (.-firstChild element)]
      (and (zero? anchor-offset)
           (or
            ;; Case 1: Empty block - anchor is the contenteditable itself
            (= anchor-node element)
            ;; Case 2: Cursor in first text node
            (= anchor-node first-child)
            ;; Case 3: Complex DOM - verify no text before cursor
            (when anchor-node
              (let [range (.createRange js/document)]
                (.setStart range element 0)
                (.setEnd range anchor-node anchor-offset)
                (zero? (count (.toString range))))))))))

(defn- detect-at-end?
  "Detect if cursor is at the very end of contenteditable.

   Returns boolean."
  [element]
  (when-let [selection (.getSelection js/window)]
    (let [text-content (.-textContent element)
          anchor-offset (.-anchorOffset selection)]
      (= anchor-offset (count text-content)))))

;; ── Selection State ───────────────────────────────────────────────────────────

(defn- detect-selection-state
  "Detect text selection state.

   Returns:
   {:has-selection? bool      - true if text is selected (non-collapsed)
    :anchor-offset int        - selection anchor position
    :focus-offset int         - selection focus position
    :selection-start int      - min of anchor/focus
    :selection-end int}       - max of anchor/focus"
  []
  (when-let [selection (.getSelection js/window)]
    (let [anchor (.-anchorOffset selection)
          focus (.-focusOffset selection)
          has-selection? (not= anchor focus)]
      {:has-selection? has-selection?
       :anchor-offset anchor
       :focus-offset focus
       :selection-start (min anchor focus)
       :selection-end (max anchor focus)})))

;; ── Main API ──────────────────────────────────────────────────────────────────

(defn boundary-state
  "Compute all cursor boundaries in ONE DOM read.

   Call once per keydown event, pass result to all handlers.

   Args:
     element - contenteditable DOM element (.-target of keydown event)
     event   - (optional) KeyboardEvent for isComposing fallback

   Returns:
     {:at-start?       bool   - cursor at position 0
      :at-end?         bool   - cursor at end of text
      :first-row?      bool   - cursor on first visual row
      :last-row?       bool   - cursor on last visual row
      :has-selection?  bool   - text is selected (non-collapsed)
      :cursor-pos      int    - current cursor offset
      :selection-start int    - selection start (= cursor-pos if no selection)
      :selection-end   int    - selection end
      :is-composing?   bool   - IME composition active
      :text-length     int}   - total text length

   Returns nil if element is nil or selection unavailable."
  ([element]
   (boundary-state element nil))
  ([element event]
   (when element
     (let [;; Row position (for vertical arrows)
           row-pos (detect-row-position element)

           ;; Selection state
           sel-state (detect-selection-state)

           ;; Horizontal boundaries (for left/right arrows)
           at-start? (detect-at-start? element)
           at-end? (detect-at-end? element)

           ;; IME state - check both our tracker and event property
           event-composing? (when event (.-isComposing event))
           is-composing? (or (composing?) event-composing?)

           ;; Text info
           text-content (.-textContent element)]

       (merge
        {:at-start? (boolean at-start?)
         :at-end? (boolean at-end?)
         :is-composing? (boolean is-composing?)
         :text-length (count text-content)
         :text-content text-content}
        row-pos
        sel-state)))))

(defn at-horizontal-boundary?
  "Check if cursor is at horizontal boundary for given direction.

   Convenience wrapper for handlers that only care about one direction."
  [bounds direction]
  (case direction
    :left (:at-start? bounds)
    :right (:at-end? bounds)
    false))

(defn at-vertical-boundary?
  "Check if cursor is at vertical boundary for given direction.

   Convenience wrapper for handlers that only care about one direction."
  [bounds direction]
  (case direction
    :up (:first-row? bounds)
    :down (:last-row? bounds)
    false))
