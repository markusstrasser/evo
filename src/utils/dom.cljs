(ns utils.dom
  "DOM interop helpers for contenteditable and selection operations.

   Extracts common patterns from components/block.cljs to reduce repetition
   and centralize DOM API access.")

;; ── Selection Helpers ───────────────────────────────────────────────────────

(defn safe-selection
  "Get window selection if it exists and has ranges.
   Returns nil if no valid selection available."
  []
  (when-let [sel (.getSelection js/window)]
    (when (pos? (.-rangeCount sel))
      sel)))

(defn selection-offset
  "Get cursor offset from current selection.
   Returns nil if no valid selection."
  []
  (when-let [sel (safe-selection)]
    (.-anchorOffset sel)))

(defn selection-node
  "Get anchor node from current selection.
   Returns nil if no valid selection."
  []
  (when-let [sel (safe-selection)]
    (.-anchorNode sel)))

(defn collapse-to-start!
  "Collapse selection to start position."
  []
  (when-let [sel (safe-selection)]
    (.collapseToStart sel)))

(defn collapse-to-end!
  "Collapse selection to end position."
  []
  (when-let [sel (safe-selection)]
    (.collapseToEnd sel)))

;; ── Event Destructuring ─────────────────────────────────────────────────────

(defn event-modifiers
  "Extract modifier key state from keyboard event.

   Returns map with:
   - :shift? - Shift key pressed
   - :mod?   - Cmd (Mac) or Ctrl (Win/Linux) pressed
   - :alt?   - Alt/Option key pressed
   - :ctrl?  - Ctrl key specifically pressed"
  [e]
  {:shift? (.-shiftKey e)
   :mod? (or (.-metaKey e) (.-ctrlKey e))
   :alt? (.-altKey e)
   :ctrl? (.-ctrlKey e)})

(defn event-key-info
  "Extract full keyboard event info including key and modifiers.

   Returns map with:
   - :key    - The key value (e.g., \"ArrowUp\", \"a\")
   - :shift? - Shift key pressed
   - :mod?   - Cmd/Ctrl pressed
   - :alt?   - Alt/Option pressed
   - :ctrl?  - Ctrl specifically pressed
   - :target - Event target element"
  [e]
  (merge {:key (.-key e)
          :target (.-target e)}
         (event-modifiers e)))

;; ── Range Helpers ───────────────────────────────────────────────────────────

(defn create-range
  "Create a new DOM Range object."
  []
  (.createRange js/document))

(defn get-current-range
  "Get the current selection range, if any."
  []
  (when-let [sel (safe-selection)]
    (.getRangeAt sel 0)))

(defn set-selection-range!
  "Set the window selection to the given range."
  [range]
  (when-let [sel (.getSelection js/window)]
    (.removeAllRanges sel)
    (.addRange sel range)))

;; ── Element Helpers ─────────────────────────────────────────────────────────

(defn text-content
  "Get text content of an element. Nil-safe."
  [elem]
  (when elem
    (.-textContent elem)))

(defn text-length
  "Get length of element's text content. Returns 0 if nil."
  [elem]
  (count (text-content elem)))

(defn focus!
  "Focus an element. Nil-safe."
  [elem]
  (when elem
    (.focus elem)))

(defn scroll-into-view!
  "Scroll element into view with minimal disruption.
   Uses 'nearest' to avoid jarring jumps."
  [elem]
  (when elem
    (.scrollIntoView elem #js {:block "nearest" :inline "nearest"})))

(defn active-element
  "Get the currently focused element."
  []
  (.-activeElement js/document))

(defn focused?
  "Check if element is currently focused."
  [elem]
  (= elem (active-element)))
