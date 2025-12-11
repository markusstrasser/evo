(ns components.block
  "Block component with uncontrolled contenteditable architecture.

   ARCHITECTURE: Uncontrolled editing - browser owns text state during edit mode.
   - View mode: Controlled by DB (text rendered from DB)
   - Edit mode: Uncontrolled (browser manages text, syncs to buffer, commits on blur)
   - Buffer: High-velocity keystroke storage (no history/indexing overhead)"
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [parser.page-refs :as page-refs]
            [parser.block-format :as block-format]
            [components.page-ref :as page-ref]
            [components.autocomplete :as autocomplete]
            [utils.text-selection :as text-sel]
            [utils.dom :as dom]
            [shell.view-state :as vs]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- extract-text-with-newlines
  "Extract text from contenteditable, converting BR elements to \\n.

   Unlike textContent (which ignores BRs), this preserves newlines from Shift+Enter.
   Unlike element->text, this doesn't add a trailing newline."
  [element]
  (let [text (text-sel/element->text element)]
    ;; element->text adds trailing \n as contenteditable quirk fix - remove it
    (if (str/ends-with? text "\n")
      (subs text 0 (dec (count text)))
      text)))

(defn- text->html
  "Convert plain text to HTML-safe string with newlines as <br>.

   Escapes HTML special characters to prevent XSS, then converts \\n to <br>."
  [text]
  (-> (or text "")
      (str/replace #"&" "&amp;")
      (str/replace #"<" "&lt;")
      (str/replace #">" "&gt;")
      (str/replace #"\n" "<br>")))

;; ── Cursor row detection ──────────────────────────────────────────────────────

(defn- detect-cursor-row-position
  "Detect if cursor is on first/last row of contenteditable using Range API.
   Returns {:first-row? bool :last-row? bool}

   With uncontrolled editable architecture, we use DOM Range API directly."
  [elem]
  (when elem
    (let [selection (.getSelection js/window)]
      (when (and selection (> (.-rangeCount selection) 0))
        (let [range (.getRangeAt selection 0)
              rect (.getBoundingClientRect range)
              elem-rect (.getBoundingClientRect elem)
              cursor-top (.-top rect)
              elem-top (.-top elem-rect)
              elem-bottom (.-bottom elem-rect)
              line-height (or (when rect (.-height rect)) 20)

              ;; Cursor is on first row if it's within one line-height of element top
              first-row? (< (- cursor-top elem-top) line-height)

              ;; Cursor is on last row if it's within one line-height of element bottom
              last-row? (< (- elem-bottom cursor-top) (* 1.5 line-height))]

          {:first-row? first-row?
           :last-row? last-row?})))))

(defn- set-cursor!
  "Set cursor in a contenteditable node at position `pos`.
   
   Handles edge cases:
   - Empty nodes (creates text node if needed)
   - Multi-node structures (BR elements from newlines)
   - Out-of-bounds positions (clamps to valid range)"
  [node pos]
  (when node
    ;; Ensure text node exists (empty blocks have no children after innerHTML=\"\")
    (when (zero? (.-length (.-childNodes node)))
      (.appendChild node (.createTextNode js/document "")))
    (let [text-length (count (.-textContent node))
          safe-pos (-> pos (max 0) (min text-length))
          range (.createRange js/document)
          sel (.getSelection js/window)]
      ;; Walk through child nodes to find the right text node and offset
      ;; This handles BR elements from newlines (each BR adds 1 to logical position)
      (loop [children (array-seq (.-childNodes node))
             remaining-pos safe-pos]
        (if-let [child (first children)]
          (let [node-type (.-nodeType child)]
            (cond
              ;; Text node (nodeType 3)
              (= node-type 3)
              (let [child-length (.-length child)]
                (if (<= remaining-pos child-length)
                  ;; Found the right text node
                  (do
                    (.setStart range child remaining-pos)
                    (.setEnd range child remaining-pos))
                  ;; Move to next node
                  (recur (rest children) (- remaining-pos child-length))))

              ;; BR element counts as 1 character (newline)
              (and (= node-type 1) (= (.-tagName child) "BR"))
              (if (zero? remaining-pos)
                ;; Cursor right before BR - put at end of previous text or start
                (let [prev (.-previousSibling child)]
                  (if (and prev (= (.-nodeType prev) 3))
                    (do
                      (.setStart range prev (.-length prev))
                      (.setEnd range prev (.-length prev)))
                    (do
                      (.setStart range node 0)
                      (.setEnd range node 0))))
                ;; Account for BR and continue
                (recur (rest children) (dec remaining-pos)))

              ;; Other element - skip
              :else
              (recur (rest children) remaining-pos)))
          ;; No more children - put cursor at end
          (let [last-child (.-lastChild node)]
            (if (and last-child (= (.-nodeType last-child) 3))
              (do
                (.setStart range last-child (.-length last-child))
                (.setEnd range last-child (.-length last-child)))
              (do
                (.setStart range node (.-length (.-childNodes node)))
                (.setEnd range node (.-length (.-childNodes node))))))))
      (.removeAllRanges sel)
      (.addRange sel range))))

(defn- insert-linebreak!
  "Insert a line break at cursor and sync to buffer.
   
   Used by both doc-mode Enter and normal-mode Shift+Enter."
  [e target block-id]
  (.preventDefault e)
  (.execCommand js/document "insertLineBreak" false nil)
  (let [new-text (extract-text-with-newlines target)]
    (vs/buffer-set! block-id new-text)))

(defn- collapse-selection-start!
  "Collapse text selection to start, preventing default event."
  [e]
  (.preventDefault e)
  (dom/collapse-to-start!))

(defn- collapse-selection-end!
  "Collapse text selection to end, preventing default event."
  [e]
  (.preventDefault e)
  (dom/collapse-to-end!))

(defn- shift-click-select-range!
  "Handle Shift+Click to select visible range from anchor to clicked block.

   LOGSEQ PARITY (FR-Pointer-01): Respects folding/zoom/page visibility."
  [db block-id on-intent]
  (let [sess @vs/!view-state
        anchor (get-in sess [:selection :anchor])]
    (if anchor
      ;; Has anchor: select range with clicked block as new focus
      (let [range-set (q/visible-range db sess anchor block-id)
            other-blocks (vec (disj range-set block-id))
            range-vec (conj other-blocks block-id)]
        (on-intent {:type :selection :mode :extend :ids range-vec}))
      ;; No anchor: just extend with this block
      (on-intent {:type :selection :mode :extend :ids block-id}))))

;; ── Drag & Drop Helpers ────────────────────────────────────────────────────────

(defn- compute-drop-zone
  "Compute drop zone based on mouse position relative to block element.

   Logseq parity:
   - Top 16px → :above (sibling above)
   - Right offset >50px from bullet → :nested (child)
   - Default → :below (sibling below)"
  [e block-element]
  (let [rect (.getBoundingClientRect block-element)
        mouse-y (.-clientY e)
        mouse-x (.-clientX e)
        relative-y (- mouse-y (.-top rect))
        relative-x (- mouse-x (.-left rect))]
    (cond
      ;; Top 16px → place above
      (< relative-y 16) :above
      ;; Right offset >50px → nest inside
      (> relative-x 50) :nested
      ;; Default → place below
      :else :below)))

(defn- handle-drag-start
  "Start dragging. If block is selected, drag all selected blocks.
   
   CRITICAL: Must stopPropagation to prevent parent blocks from
   overwriting the drag state when dragging child blocks."
  [e block-id]
  (.stopPropagation e) ; Prevent bubbling to parent blocks!
  (let [selection (vs/selection-nodes)
        drag-ids (if (contains? selection block-id)
                   selection
                   #{block-id})]
    ;; Set drag data (required for drag to work)
    (.setData (.-dataTransfer e) "text/plain" (pr-str drag-ids))
    (set! (.-effectAllowed (.-dataTransfer e)) "move")
    ;; Update session
    (vs/drag-start! drag-ids)))

(defn- handle-drag-enter
  "Allow drop by preventing default on dragenter."
  [e block-id]
  (.preventDefault e)
  (.stopPropagation e) ; Prevent bubbling to parent blocks
  ;; Also update drop target on enter for immediate feedback
  (let [target (.-currentTarget e)
        zone (compute-drop-zone e target)
        dragging (vs/dragging-ids)]
    (when-not (contains? dragging block-id)
      (vs/drag-over! block-id zone))))

(defn- handle-drag-over
  "Update drop target based on mouse position."
  [e block-id]
  (.preventDefault e) ; Required for drop to work
  (.stopPropagation e) ; Prevent bubbling to parent blocks
  (set! (.-dropEffect (.-dataTransfer e)) "move")
  (let [target (.-currentTarget e)
        zone (compute-drop-zone e target)
        dragging (vs/dragging-ids)]
    ;; Don't allow dropping on self
    (when-not (contains? dragging block-id)
      (vs/drag-over! block-id zone))))

(defn- handle-drag-leave
  "Clear drop target when leaving."
  [_e]
  ;; Only clear if we're actually leaving (not entering a child)
  ;; The dragover on new target will set the new drop target
  nil)

(defn- handle-drop
  "Execute move on drop."
  [e db block-id on-intent]
  (.preventDefault e)
  (.stopPropagation e)
  ;; Capture state immediately before any async operations
  (let [dragging (vs/dragging-ids)
        drop-target (vs/drop-target)
        zone (:zone drop-target)]
    ;; Clear drag state first to prevent race conditions
    (vs/drag-end!)
    ;; Execute move if valid
    (when (and (seq dragging)
               (not (contains? dragging block-id))
               zone) ; Must have a valid zone
      (let [parent-id (get-in db [:derived :parent-of block-id])
            ;; Convert zone to move intent params
            ;; Note: :above uses {:before} which is supported by kernel.position
            [target-parent anchor] (case zone
                                     :above [parent-id {:before block-id}]
                                     :below [parent-id {:after block-id}]
                                     :nested [block-id :first]
                                     ;; Default: place after (shouldn't happen)
                                     [parent-id {:after block-id}])]
        (on-intent {:type :move
                    :selection (vec dragging)
                    :parent target-parent
                    :anchor anchor})))))

(defn- handle-drag-end
  "Clean up drag state."
  [e]
  (.stopPropagation e) ; Prevent bubbling to parent blocks
  (vs/drag-end!))

(defn- drop-zone-style
  "Visual feedback style for drop zones."
  [block-id]
  (let [{:keys [id zone]} (vs/drop-target)]
    (when (= id block-id)
      (case zone
        :above {:border-top "2px solid #0066cc"}
        :below {:border-bottom "2px solid #0066cc"}
        :nested {:background-color "#e6f0ff"
                 :border-left "3px solid #0066cc"}
        nil))))

;; ── Keyboard handlers ─────────────────────────────────────────────────────────

(defn- handle-vertical-arrow
  "Parametric handler for ArrowUp/ArrowDown navigation.

   Args:
   - direction: :up or :down
   - e, _db, block-id, on-intent: standard handler args"
  [direction e _db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)
        [boundary-key collapse-fn intent-key cursor-row]
        (if (= direction :up)
          [:first-row? collapse-selection-start! :editing/navigate-up :first]
          [:last-row? collapse-selection-end! :editing/navigate-down :last])]
    (cond
      ;; Has text selection - collapse appropriately
      (text-sel/selection-present?)
      (collapse-fn e)

      ;; At boundary row - navigate to adjacent block with cursor memory
      (get cursor-pos boundary-key)
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            (on-intent [[intent-key {:block-id block-id
                                     :current-text text-content
                                     :current-cursor-pos cursor-offset
                                     :cursor-row cursor-row}]])))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-arrow-up [e db block-id on-intent]
  (handle-vertical-arrow :up e db block-id on-intent))

(defn handle-arrow-down [e db block-id on-intent]
  (handle-vertical-arrow :down e db block-id on-intent))

(defn- handle-shift-vertical-arrow
  "Parametric handler for Shift+ArrowUp/Down while editing: exit edit and extend selection.

   Logseq parity: Shift+Arrow ALWAYS exits edit mode and starts block selection.
   (No text selection within blocks - that's what mouse drag is for)

   Buffer auto-commit: Handled automatically by kernel.intent/apply-intent via
   :pending-buffer injection in shell.executor. No manual commit needed here.

   Note: db and block-id unused here; standard keyboard handler signature kept for consistency."
  [direction e _db _block-id on-intent]
  (.preventDefault e)
  ;; Collapse any text selection before exiting
  (when-let [sel (.getSelection js/window)]
    (when (and sel (pos? (.-rangeCount sel)))
      (if (= direction :up)
        (.collapseToStart sel)
        (.collapseToEnd sel))))
  ;; Exit edit, select block, and extend in direction
  (on-intent {:type :exit-edit-and-extend
              :direction (if (= direction :up) :prev :next)}))

(defn handle-shift-arrow-up
  "Handle Shift+Up: text selection within block OR block selection at boundary."
  [e db block-id on-intent]
  (handle-shift-vertical-arrow :up e db block-id on-intent))

(defn handle-shift-arrow-down
  "Handle Shift+Down: text selection within block OR block selection at boundary."
  [e db block-id on-intent]
  (handle-shift-vertical-arrow :down e db block-id on-intent))

(defn handle-arrow-left
  "Handle left arrow key.

   Behaviors:
   - Has text selection → Collapse to start
   - At start of block → Edit previous block at end
   - Middle of text → Move cursor left (browser default)"
  [e _db block-id on-intent]
  (let [target (.-target e)
        selection (.getSelection js/window)
        anchor-offset (.-anchorOffset selection)
        anchor-node (.-anchorNode selection)
        first-child (.-firstChild target)
        ;; More robust at-start? check:
        ;; We're at start if offset is 0 AND we're in the first text position
        ;; This handles cases where anchorNode might not === firstChild
        range-text (when (and anchor-node (zero? anchor-offset))
                     (let [range (.createRange js/document)]
                       (.setStart range target 0)
                       (.setEnd range anchor-node anchor-offset)
                       (.toString range)))
        at-start? (and (zero? anchor-offset)
                       (or
                        ;; Case 1: anchorNode is the contenteditable itself (empty block)
                        (= anchor-node target)
                        ;; Case 2: anchorNode is the first child
                        (= anchor-node first-child)
                        ;; Case 3: Check by seeing if there's any text content before our position
                        (and range-text (zero? (count range-text)))))]
    (cond
      ;; Has selection - collapse to start
      (text-sel/selection-present?)
      (collapse-selection-start! e)

      ;; At start - navigate to previous block
      at-start?
      (do (.preventDefault e)
          (on-intent {:type :navigate-to-adjacent
                      :direction :up
                      :current-block-id block-id
                      :cursor-position :max})) ; Enter previous at end

      ;; Middle - let browser handle
      :else nil)))

(defn handle-arrow-right
  "Handle right arrow key.

   Behaviors:
   - Has text selection → Collapse to end
   - At end of block → Edit next block at start
   - Middle of text → Move cursor right (browser default)"
  [e _db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        at-end? (= (.-anchorOffset selection) (count text-content))]
    (cond
      ;; Has selection - collapse to end
      (text-sel/selection-present?)
      (collapse-selection-end! e)

      ;; At end - navigate to next block
      at-end?
      (do (.preventDefault e)
          (on-intent {:type :navigate-to-adjacent
                      :direction :down
                      :current-block-id block-id
                      :cursor-position 0})) ; Enter next at start

      ;; Middle - let browser handle
      :else nil)))

(defn handle-enter [e db block-id on-intent]
  (.preventDefault e)
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        ;; LOGSEQ PARITY: Empty block auto-outdent
        ;; Conditions: empty content + no next sibling + not at top level
        is-empty? (str/blank? text-content)
        has-next? (some? (q/next-sibling db block-id))
        parent (q/parent-of db block-id)
        at-root? (= parent :doc)]
    (if (and is-empty? (not has-next?) (not at-root?))
      ;; Auto-outdent: move block to be sibling of parent
      ;; Use :outdent-selected which preserves editing state
      (on-intent {:type :outdent-selected})
      ;; Normal: create new block via smart-split
      (on-intent [[:editing/smart-split {:block-id block-id
                                         :cursor-pos cursor-pos}]]))))

(defn handle-escape [e _db block-id on-intent]
  (.preventDefault e)
  (.stopPropagation e) ; Prevent global keydown from also handling Escape
  ;; First, commit any unsaved content (blur might not fire reliably when unmounting)
  ;; Read from buffer (more reliable than DOM textContent with Playwright)
  ;; Fallback to DOM with extract-text-with-newlines to preserve BR as \n
  (let [buffer-text (vs/buffer-text block-id)
        dom-text (extract-text-with-newlines (.-target e))
        final-text (or buffer-text dom-text)]
    (when final-text
      (on-intent {:type :update-content
                  :block-id block-id
                  :text final-text})))
  ;; Then exit edit mode via Nexus action
  (on-intent [[:editing/escape {:block-id block-id}]]))

(defn handle-backspace [e _db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        is-at-start (and selection (= (.-anchorOffset selection) 0))]
    (when (or (empty? text-content) is-at-start)
      (.preventDefault e)
      (if (empty? text-content)
        ;; Empty block - delete and navigate to prev
        (on-intent {:type :delete :id block-id})
        ;; At start with content - merge with previous
        ;; CRITICAL: Pass current text from DOM (buffer may not be synced to DB yet)
        (on-intent {:type :merge-with-prev :block-id block-id :text text-content})))))

(defn handle-delete
  "Handle Delete key - merge with next block if at end."
  [e _db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        is-at-end (= (.-anchorOffset selection) (count text-content))]
    (when is-at-end
      (.preventDefault e)
      ;; CRITICAL: Pass current text from DOM (buffer may not be synced to DB yet)
      (on-intent {:type :merge-with-next :block-id block-id :text text-content}))))

;; ── Autocomplete Helpers ──────────────────────────────────────────────────────

(defn- detect-page-ref-trigger
  "Detect if user just typed [[ to trigger page-ref autocomplete.

   Returns trigger-pos (position after [[) if triggered, nil otherwise.

   Logic: Check if previous 2 chars before cursor are [["
  [text cursor-pos]
  (when (>= cursor-pos 2)
    (let [prev-two (subs text (- cursor-pos 2) cursor-pos)]
      (when (= prev-two "[[")
        cursor-pos)))) ; Return position after [[

(defn- detect-slash-command-trigger
  "Detect if user just typed / to trigger slash command autocomplete.

   Returns trigger-pos (position after /) if triggered, nil otherwise.

   Logic:
   - Previous char is /
   - / is at start of text OR preceded by whitespace
   - Not inside an existing word"
  [text cursor-pos]
  (when (>= cursor-pos 1)
    (let [prev-char (subs text (dec cursor-pos) cursor-pos)]
      (when (= prev-char "/")
        ;; Check that / is at start or after whitespace
        (let [at-start? (= cursor-pos 1)
              after-space? (and (> cursor-pos 1)
                                (let [before-slash (subs text (- cursor-pos 2) (dec cursor-pos))]
                                  (or (= before-slash " ")
                                      (= before-slash "\n")
                                      (= before-slash "\t"))))]
          (when (or at-start? after-space?)
            cursor-pos))))))

(defn- handle-autocomplete-keydown
  "Handle keyboard events when autocomplete is active.
   
   Returns true if event was handled (and should not propagate),
   false otherwise.
   
   For Enter/Tab selection: Handles DOM manipulation directly to stay in edit mode.
   This is necessary because browser owns the DOM during editing - we can't just
   update the DB and expect the DOM to reflect it.
   
   Edge cases (Logseq parity):
   - Backspace on [[ deletes both brackets and closes popup
   - Left arrow at trigger-pos closes popup
   - Right arrow past ]] closes popup"
  [e on-intent]
  (let [key (.-key e)
        autocomplete-state (vs/autocomplete)]
    (when autocomplete-state
      (let [{:keys [trigger-pos query selected items block-id]} autocomplete-state]
        (cond
          ;; Arrow Up - navigate up in list
          (= key "ArrowUp")
          (do (.preventDefault e)
              (.stopPropagation e)
              (on-intent {:type :autocomplete/navigate :direction :up})
              true)

          ;; Arrow Down - navigate down in list  
          (= key "ArrowDown")
          (do (.preventDefault e)
              (.stopPropagation e)
              (on-intent {:type :autocomplete/navigate :direction :down})
              true)

          ;; ArrowLeft - close popup if cursor moves before trigger
          (= key "ArrowLeft")
          (let [{:keys [type trigger-length]} autocomplete-state
                trig-len (or trigger-length (if (= type :command) 1 2))
                selection (.getSelection js/window)
                cursor-pos (when selection (.-anchorOffset selection))
                ;; For page-ref: trigger-pos is after [[, so [[ starts at trigger-pos - 2
                ;; For command: trigger-pos is after /, so / starts at trigger-pos - 1
                trigger-start (- trigger-pos trig-len)
                at-or-before-trigger? (and cursor-pos (<= cursor-pos trigger-start))]
            (when at-or-before-trigger?
              (on-intent {:type :autocomplete/dismiss}))
            false)

          ;; ArrowRight - close popup if cursor moves past region
          ;; For page-ref: past ]]
          ;; For command: past word boundary (space or end of text)
          (= key "ArrowRight")
          (let [{:keys [type]} autocomplete-state
                target (.-target e)
                text (.-textContent target)
                selection (.getSelection js/window)
                cursor-pos (when selection (.-anchorOffset selection))]
            (if (= type :command)
              ;; Command mode: dismiss if moving past word boundary
              (let [query-end (or (str/index-of text " " trigger-pos)
                                  (str/index-of text "\n" trigger-pos)
                                  (count text))
                    will-be-past-end? (and cursor-pos (>= (inc cursor-pos) query-end))]
                (when will-be-past-end?
                  (on-intent {:type :autocomplete/dismiss})))
              ;; Page-ref mode: dismiss if moving past ]]
              (let [close-bracket-pos (str/index-of text "]]" trigger-pos)
                    will-be-past-close? (and cursor-pos
                                             close-bracket-pos
                                             (>= (inc cursor-pos) (+ close-bracket-pos 2)))]
                (when will-be-past-close?
                  (on-intent {:type :autocomplete/dismiss}))))
            false)

          ;; Backspace - delete trigger chars if at trigger position
          ;; For page-ref: delete both [[ and ]]
          ;; For command: delete just /
          (= key "Backspace")
          (let [{:keys [type]} autocomplete-state
                target (.-target e)
                text (.-textContent target)
                selection (.getSelection js/window)
                cursor-pos (when selection (.-anchorOffset selection))
                ;; Check if cursor is right after trigger with empty query
                at-trigger? (and cursor-pos (= cursor-pos trigger-pos) (empty? query))]
            (if at-trigger?
              (if (= type :command)
                ;; Command mode: just delete the /
                (let [start-pos (dec trigger-pos) ; Position of /
                      new-text (str (subs text 0 start-pos)
                                    (subs text trigger-pos))
                      new-cursor start-pos]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (set! (.-textContent target) new-text)
                  (vs/buffer-set! block-id new-text)
                  (set-cursor! target new-cursor)
                  (on-intent {:type :autocomplete/dismiss})
                  true)
                ;; Page-ref mode: delete both [[ and ]]
                (let [start-pos (- trigger-pos 2)
                      end-pos (+ trigger-pos 2)
                      new-text (str (subs text 0 start-pos)
                                    (subs text (min end-pos (count text))))
                      new-cursor start-pos]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (set! (.-textContent target) new-text)
                  (vs/buffer-set! block-id new-text)
                  (set-cursor! target new-cursor)
                  (on-intent {:type :autocomplete/dismiss})
                  true))
              ;; Not at trigger - let backspace through
              false))

;; Enter or Tab - select current item (Logseq parity: stay in edit mode)
          (or (= key "Enter") (= key "Tab"))
          (do (.preventDefault e)
              (.stopPropagation e)
              (let [{:keys [type]} autocomplete-state
                    item (get items selected)]
                (when item
                  (let [target (.-target e)
                        text (.-textContent target)]
                    (if (= type :command)
                      ;; Command mode: insert command output
                      (let [;; Start at / position
                            start-pos (dec trigger-pos)
                            ;; End at cursor (trigger-pos + query length)
                            end-pos (+ trigger-pos (count query))
                            ;; Get insert text from command's insert-fn
                            insert-str ((:insert-fn item))
                            ;; Apply backward-pos if specified
                            item-backward (get item :backward-pos 0)
                            ;; Build new text
                            new-text (str (subs text 0 start-pos)
                                          insert-str
                                          (subs text (min end-pos (count text))))
                            ;; New cursor: after insert, minus backward-pos
                            new-cursor (- (+ start-pos (count insert-str)) item-backward)]
                        (set! (.-textContent target) new-text)
                        (vs/buffer-set! block-id new-text)
                        (set-cursor! target new-cursor)
                        (on-intent {:type :autocomplete/dismiss})
                        (on-intent {:type :update-content
                                    :block-id block-id
                                    :text new-text}))

                      ;; Page-ref mode: original logic
                      (let [start-pos (- trigger-pos 2)
                            end-pos (+ trigger-pos (count query) 2)
                            page-title (:title item)
                            insert-str (str "[[" page-title "]]")
                            new-text (str (subs text 0 start-pos)
                                          insert-str
                                          (subs text (min end-pos (count text))))
                            new-cursor (+ start-pos (count insert-str))
                            is-create-new? (= (:type item) :create-new)]
                        (set! (.-textContent target) new-text)
                        (vs/buffer-set! block-id new-text)
                        (set-cursor! target new-cursor)
                        (on-intent {:type :autocomplete/dismiss})
                        (when is-create-new?
                          (on-intent {:type :page/create
                                      :title page-title
                                      :navigate? false}))
                        (on-intent {:type :update-content
                                    :block-id block-id
                                    :text new-text}))))))
              true)

;; Home - moves cursor to start, always outside autocomplete region
          (= key "Home")
          (do (on-intent {:type :autocomplete/dismiss})
              false)

          ;; End - moves cursor to end, always outside autocomplete region  
          (= key "End")
          (do (on-intent {:type :autocomplete/dismiss})
              false)

          ;; Escape - dismiss
          (= key "Escape")
          (do (.preventDefault e)
              (.stopPropagation e)
              (on-intent {:type :autocomplete/dismiss})
              true)

          ;; Any other key - let it through, input handler will update query
          :else false)))))

(defn- handle-autocomplete-input
  "Handle input events for autocomplete trigger and query updates.

   Called on every input event while editing.
   - Detects [[ trigger for page-ref autocomplete (auto-inserts ]])
   - Detects / trigger for slash command autocomplete
   - Updates query as user types
   - Dismisses if cursor moves outside trigger region"
  [text cursor-pos block-id on-intent]
  (let [autocomplete-state (vs/autocomplete)]
    (if autocomplete-state
      ;; Autocomplete is active - update query or dismiss
      (let [{:keys [trigger-pos type]} autocomplete-state]
        (if (= type :command)
          ;; Slash command mode - simpler region check (no closing bracket)
          (let [query-end (or (str/index-of text " " trigger-pos)
                              (str/index-of text "\n" trigger-pos)
                              (count text))
                valid-region? (and (>= cursor-pos trigger-pos)
                                   (<= cursor-pos query-end)
                                   (>= trigger-pos 1)
                                   (= (subs text (dec trigger-pos) trigger-pos) "/"))]
            (if valid-region?
              (let [new-query (subs text trigger-pos cursor-pos)]
                (on-intent {:type :autocomplete/update :query new-query}))
              (on-intent {:type :autocomplete/dismiss})))

          ;; Page-ref mode - original logic with closing bracket tracking
          (let [close-bracket-pos (str/index-of text "]]" trigger-pos)
                cursor-past-close? (and close-bracket-pos
                                        (> cursor-pos (+ close-bracket-pos 2)))
                valid-region? (and close-bracket-pos
                                   (>= cursor-pos trigger-pos)
                                   (<= cursor-pos close-bracket-pos)
                                   (>= trigger-pos 2)
                                   (= (subs text (- trigger-pos 2) trigger-pos) "[["))]
            (cond
              cursor-past-close?
              (on-intent {:type :autocomplete/dismiss})

              valid-region?
              (let [new-query (subs text trigger-pos close-bracket-pos)]
                (on-intent {:type :autocomplete/update :query new-query}))

              :else
              (on-intent {:type :autocomplete/dismiss})))))

      ;; Autocomplete not active - check for triggers
      (if-let [page-trigger (detect-page-ref-trigger text cursor-pos)]
        ;; Page-ref trigger: auto-insert ]] closing brackets (Logseq parity)
        (do
          (when-let [selection (.getSelection js/window)]
            (when-let [range (.getRangeAt selection 0)]
              (let [text-node (.-startContainer range)
                    closing "]]"]
                (when (= (.-nodeType text-node) 3)
                  (.insertData text-node cursor-pos closing)
                  (.setStart range text-node cursor-pos)
                  (.setEnd range text-node cursor-pos)
                  (.removeAllRanges selection)
                  (.addRange selection range)))))
          (on-intent {:type :autocomplete/trigger
                      :source :page-ref
                      :block-id block-id
                      :trigger-pos page-trigger}))

        ;; Check for / slash command trigger
        (when-let [cmd-trigger (detect-slash-command-trigger text cursor-pos)]
          (on-intent {:type :autocomplete/trigger
                      :source :command
                      :block-id block-id
                      :trigger-pos cmd-trigger}))))))

(defn handle-keydown
  "Handle keyboard events while editing a block.

   Delegates to editing handlers for navigation, splits, and escapes.
   When autocomplete is active, intercepts navigation keys."
  [e db block-id on-intent]
  ;; Check autocomplete first - it intercepts arrow keys, Enter, Escape, Tab
  (when-not (handle-autocomplete-keydown e on-intent)
    (let [{:keys [shift? mod? alt? ctrl?]} (dom/event-modifiers e)
          key (.-key e)
          target (.-target e)]

      (cond
        ;; === Emacs Line Navigation (macOS: Ctrl+A/E) ===
        (and (= key "a") ctrl? (not shift?) (not alt?))
        (do (.preventDefault e)
            (let [text-content (.-textContent target)
                  selection (.getSelection js/window)
                  cursor-pos (.-anchorOffset selection)
                  line-start (loop [pos (dec cursor-pos)]
                               (cond
                                 (< pos 0) 0
                                 (= (nth text-content pos) \newline) (inc pos)
                                 :else (recur (dec pos))))]
              (set-cursor! target line-start)))

        (and (= key "e") ctrl? (not shift?) (not alt?))
        (do (.preventDefault e)
            (let [text-content (.-textContent target)
                  selection (.getSelection js/window)
                  cursor-pos (.-anchorOffset selection)
                  text-length (count text-content)
                  line-end (loop [pos cursor-pos]
                             (cond
                               (>= pos text-length) text-length
                               (= (nth text-content pos) \newline) pos
                               :else (recur (inc pos))))]
              (set-cursor! target line-end)))

        ;; === Emacs Block Navigation (macOS: Alt+A/E) ===
        (and (= key "a") alt? (not shift?) (not ctrl?))
        (do (.preventDefault e)
            (set-cursor! target 0))

        (and (= key "e") alt? (not shift?) (not ctrl?))
        (do (.preventDefault e)
            (set-cursor! target (count (.-textContent target))))

        ;; Shift+Arrow - exit edit and extend block selection (Logseq parity)
        (and (= key "ArrowUp") shift? (not mod?) (not alt?))
        (handle-shift-arrow-up e db block-id on-intent)

        (and (= key "ArrowDown") shift? (not mod?) (not alt?))
        (handle-shift-arrow-down e db block-id on-intent)

        ;; Ctrl+P/N - Emacs-style navigation aliases
        (and (= key "p") ctrl? (not shift?) (not alt?))
        (handle-arrow-up e db block-id on-intent)

        (and (= key "n") ctrl? (not shift?) (not alt?))
        (handle-arrow-down e db block-id on-intent)

        ;; Plain arrows - navigate between blocks while editing
        (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?))
        (handle-arrow-up e db block-id on-intent)

        (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?))
        (handle-arrow-down e db block-id on-intent)

        (and (= key "ArrowLeft") (not shift?) (not mod?) (not alt?))
        (handle-arrow-left e db block-id on-intent)

        (and (= key "ArrowRight") (not shift?) (not mod?) (not alt?))
        (handle-arrow-right e db block-id on-intent)

        ;; LOGSEQ PARITY: Enter/Shift+Enter behavior depends on doc-mode
        ;; Normal mode: Enter = new block, Shift+Enter = newline
        ;; Doc-mode: Enter = newline, Shift+Enter = new block
        (and (= key "Enter") shift? (not mod?) (not alt?))
        (if (vs/document-view?)
          ;; Doc-mode: Shift+Enter creates new block
          (handle-enter e db block-id on-intent)
          ;; Normal mode: Shift+Enter inserts literal newline
          (insert-linebreak! e target block-id))

        ;; Enter - behavior depends on doc-mode
        (and (= key "Enter") (not shift?) (not mod?) (not alt?))
        (if (vs/document-view?)
          ;; Doc-mode: Enter inserts newline
          (insert-linebreak! e target block-id)
          ;; Normal mode: Enter creates new block
          (handle-enter e db block-id on-intent))

        ;; Escape - exit edit mode
        (= key "Escape")
        (handle-escape e db block-id on-intent)

        ;; Backspace - delete/merge at cursor boundary
        (and (= key "Backspace") (not shift?) (not mod?) (not alt?))
        (handle-backspace e db block-id on-intent)

        ;; Delete at end - merge with next block
        (and (= key "Delete") (not shift?) (not mod?) (not alt?))
        (handle-delete e db block-id on-intent)

        :else nil))))

;; ── Content Rendering with Page Refs ─────────────────────────────────────────

(defn- text-segment->hiccup
  "Convert a text string to hiccup, replacing \\n with [:br]."
  [text]
  (if (str/includes? text "\n")
    (let [parts (str/split text #"\n" -1)] ; -1 keeps trailing empties
      (interpose [:br] (map #(if (empty? %) "" %) parts)))
    text))

(defn- render-text-with-page-refs
  "Render text with [[page-refs]] as clickable PageRef components.

   Uses canonical parser from parser.page-refs.
   Handles newlines (converts to [:br]).
   Returns hiccup children for a span container."
  [db text on-intent]
  (when text
    (->> (page-refs/split-with-refs text)
         (mapcat (fn [{:keys [type value page]}]
                   (case type
                     :text (let [result (text-segment->hiccup value)]
                             (if (seq? result) result [result]))
                     :page-ref [(page-ref/PageRef {:db db
                                                   :page-name page
                                                   :on-intent on-intent})]))))))

;; ── Content Mode Helpers ──────────────────────────────────────────────────────

(defn- edit-content
  "Edit mode content: uncontrolled contenteditable.
   
   Browser owns text state. Syncs to buffer on input, commits on blur.
   Lifecycle hooks handle focus and cursor positioning."
  [{:keys [block-id text on-intent db]}]
  (let [edit-key (str block-id "-edit")]
    [:span.block-content
     {:contentEditable true
      :suppressContentEditableWarning true
      :replicant/key edit-key

      ;; On mount: Set text once, focus, position cursor
      :replicant/on-mount
      (fn [{:replicant/keys [node]}]
        (let [initial-cursor (vs/cursor-position)
              cursor-pos (cond
                           (number? initial-cursor) initial-cursor
                           (= initial-cursor :start) 0
                           (= initial-cursor :end) (count text)
                           (= initial-cursor :max) (count text)
                           :else (count text))]

          ;; Mark that on-mount has fired (on-render runs BEFORE on-mount in Replicant!)
          ;; This tells on-render it can now handle cursor-position for subsequent renders
          (set! (.-dataset node) (clj->js {:mounted "true"}))

          ;; Set text content (browser owns this from now on)
          ;; Convert \n to <br> so newlines are visually rendered
          (set! (.-innerHTML node) (text->html text))

          ;; Focus UNCONDITIONALLY (required side effect, never conditional)
          (.focus node)

          ;; Scroll into view - ensures block is visible when navigating with arrows
          ;; Use "nearest" to avoid jarring jumps when block is already partially visible
          (.scrollIntoView node #js {:block "nearest" :inline "nearest"})

          ;; Position cursor (set-cursor! handles empty text node edge case)
          (set-cursor! node cursor-pos)

          ;; Clear cursor-position AFTER applying it (on-mount is the authority for new elements)
          (when initial-cursor
            (vs/clear-cursor-position!))))

      ;; On render: Maintain focus AND apply pending cursor position
      ;; LOGSEQ PARITY (FR-Undo-01): After undo/redo, cursor-position is set in session
      ;; We need to apply it to the DOM here since on-mount only fires on first render
      :replicant/on-render
      (fn [{:replicant/keys [node]}]
        ;; Focus if needed
        (when (not= (.-activeElement js/document) node)
          (.focus node)
          ;; Also scroll into view when focus changes (e.g., after undo/redo)
          (.scrollIntoView node #js {:block "nearest" :inline "nearest"}))

        ;; Apply pending cursor position from session (set by undo/redo)
        ;; IMPORTANT: Only handle cursor-position if:
        ;; 1. The element was already mounted (on-render fires BEFORE on-mount in Replicant!)
        ;; 2. This block is the current editing block
        ;; For NEW elements, on-mount handles cursor positioning.
        (let [mounted? (.-mounted ^js (.-dataset node))]
          (when (and mounted? (= block-id (vs/editing-block-id)))
            (when-let [pending-cursor (vs/cursor-position)]
              (let [text-content (.-textContent node)
                    text-length (count text-content)
                    cursor-pos (cond
                                 (number? pending-cursor) (min pending-cursor text-length)
                                 (= pending-cursor :start) 0
                                 (= pending-cursor :end) text-length
                                 (= pending-cursor :max) text-length
                                 :else nil)]
                (when cursor-pos
                  (set-cursor! node cursor-pos))
                ;; Clear the pending cursor position to prevent reapplication
                (vs/clear-cursor-position!))))))

      ;; Event handlers for edit mode
      :on {:click (fn [e]
                    ;; IMPORTANT: Stop propagation to prevent container's selection handler
                    ;; When clicking inside contenteditable, we just want cursor positioning
                    (.stopPropagation e))

           ;; Input handler: Update buffer and check autocomplete triggers
           :input (fn [e]
                    (let [target (.-target e)
                          ;; Use extract-text-with-newlines to preserve BR as \n (from Shift+Enter)
                          new-text (extract-text-with-newlines target)
                          selection (.getSelection js/window)
                          cursor-pos (when selection (.-anchorOffset selection))]
                      ;; Update buffer atom directly - does NOT trigger re-render
                      (vs/buffer-set! block-id new-text)
                      ;; Check for autocomplete triggers (e.g., [[)
                      (when cursor-pos
                        (handle-autocomplete-input new-text cursor-pos block-id on-intent))))

           ;; Blur handler: Commit to canonical DB
           :blur (fn [e]
                   (let [target (.-target e)
                         final-text (extract-text-with-newlines target)
                         suppress? (vs/keep-edit-on-blur?)
                         same-block? (= (vs/editing-block-id) block-id)]
                     ;; Dismiss autocomplete if active
                     (when (vs/autocomplete-active?)
                       (on-intent {:type :autocomplete/dismiss}))
                     ;; CRITICAL: Only commit text if we're still editing THIS block.
                     ;; If editing moved to a different block (e.g., after paste),
                     ;; the handler already updated this block - don't overwrite with stale DOM text.
                     (when same-block?
                       (on-intent {:type :update-content
                                   :block-id block-id
                                   :text final-text})
                       ;; Exit edit mode unless in a structural operation (indent/outdent/move)
                       (when (not suppress?)
                         (on-intent {:type :exit-edit})))))

           ;; Keydown: Keyboard shortcuts
           :keydown (fn [e]
                      (handle-keydown e db block-id on-intent))

           ;; Paste: Multi-paragraph splitting (Logseq parity)
           ;; CRITICAL: For simple inline paste, update DOM/buffer/cursor directly.
           ;; For multi-block paste (markdown/blank lines), let the intent handle it.
           ;; INTERNAL FORMAT: When pasting from our own copy/cut, use clipboard-blocks
           ;; which preserves exact hierarchy (siblings stay siblings).
           :paste (fn [e]
                    (.preventDefault e)
                    (let [target (.-target e)
                          clipboard-data (.-clipboardData e)
                          pasted-text (.getData clipboard-data "text/plain")
                          selection (.getSelection js/window)
                          anchor-offset (.-anchorOffset selection)
                          focus-offset (.-focusOffset selection)
                          selection-start (min anchor-offset focus-offset)
                          selection-end (max anchor-offset focus-offset)
                          ;; Check if this paste matches our internal clipboard
                          ;; (same text means it came from our copy/cut)
                          internal-text (vs/clipboard-text)
                          internal-blocks (vs/clipboard-blocks)
                          use-internal? (and (seq internal-blocks)
                                             (= pasted-text internal-text))
                          ;; Detect multi-block paste (markdown or blank lines)
                          is-markdown? (boolean (re-find #"(?m)^\s*[-*+]\s+" pasted-text))
                          has-blank-lines? (boolean (re-find #"\n\n" pasted-text))
                          is-multi-block? (or use-internal? is-markdown? has-blank-lines?)
                          current-text (extract-text-with-newlines target)]

                      (if is-multi-block?
                        ;; Multi-block paste: Commit DOM text first, then dispatch intent
                        ;; CRITICAL: :paste-text requires :editing state - do NOT exit first!
                        (do
                          ;; 1. Sync DOM → DB before paste (uncontrolled mode)
                          (on-intent {:type :update-content
                                      :block-id block-id
                                      :text current-text})
                          ;; 2. Dispatch paste - handler updates editing-block-id & cursor
                          ;; Include internal clipboard-blocks if available (preserves structure)
                          (on-intent (cond-> {:type :paste-text
                                              :block-id block-id
                                              :cursor-pos selection-start
                                              :selection-end selection-end
                                              :pasted-text pasted-text}
                                       use-internal? (assoc :clipboard-blocks internal-blocks))))

                        ;; Simple inline paste: Update DOM/buffer directly for responsiveness
                        (let [before (subs current-text 0 selection-start)
                              after (subs current-text selection-end)
                              new-text (str before pasted-text after)
                              new-cursor-pos (+ selection-start (count pasted-text))]
                          ;; 1. Update DOM directly
                          (set! (.-innerHTML target) (text->html new-text))
                          ;; 2. Update buffer
                          (vs/buffer-set! block-id new-text)
                          ;; 3. Position cursor
                          (set-cursor! target new-cursor-pos)
                          ;; 4. Update DB
                          (on-intent {:type :paste-text
                                      :block-id block-id
                                      :cursor-pos selection-start
                                      :selection-end selection-end
                                      :pasted-text pasted-text})))))}}]))

(defn- extract-tweet-id
  "Extract tweet ID from X/Twitter URL."
  [url]
  (when url
    (second (re-find #"/status/(\d+)" url))))

(defn- extract-username
  "Extract username from X/Twitter URL."
  [url]
  (when url
    (or (second (re-find #"x\.com/([^/]+)/status" url))
        (second (re-find #"twitter\.com/([^/]+)/status" url)))))

;; Tweet data cache - stores oEmbed results by URL
(defonce !tweet-cache (atom {}))

(defn- fetch-tweet-oembed!
  "Fetch tweet data via Twitter oEmbed API (no auth needed).
   Returns author name - actual tweet content requires Twitter API auth."
  [url on-complete]
  (when-not (get @!tweet-cache url)
    ;; Mark as loading
    (swap! !tweet-cache assoc url {:loading? true})
    (-> (js/fetch (str "https://publish.twitter.com/oembed?url=" (js/encodeURIComponent url)))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (throw (js/Error. "Failed to fetch tweet")))))
        (.then (fn [^js data]
                 (swap! !tweet-cache assoc url
                        {:author-name (.-author_name data)
                         :author-url (.-author_url data)
                         :loading? false})
                 (when on-complete (on-complete))))
        (.catch (fn [_err]
                  (swap! !tweet-cache assoc url {:error? true :loading? false})
                  (when on-complete (on-complete)))))))

(defn- tweet-embed
  "Render a compact tweet preview card."
  [{:keys [block-id url is-focused on-intent db]}]
  (let [tweet-id (extract-tweet-id url)
        username (extract-username url)
        cached (get @!tweet-cache url)
        view-key (str block-id "-tweet")
        click-handler {:on {:click (fn [e]
                                     (.stopPropagation e)
                                     (cond
                                       (.-shiftKey e)
                                       (shift-click-select-range! db block-id on-intent)

                                       is-focused
                                       (on-intent {:type :enter-edit :block-id block-id})

                                       :else
                                       (on-intent {:type :selection :mode :replace :ids block-id})))}}
        ;; Use cached author name if available, fallback to username
        display-name (or (:author-name cached) (str "@" username) "@unknown")]
    [:a.tweet-embed
     (merge {:replicant/key view-key
             :href url
             :target "_blank"
             :rel "noopener noreferrer"
             ;; Fetch author info on mount
             :replicant/on-mount (fn [_node]
                                   (fetch-tweet-oembed! url nil))}
            click-handler)
     [:div.tweet-embed-header
      [:span.tweet-icon "𝕏"]
      [:span.tweet-author display-name]]
     [:div.tweet-embed-body
      (cond
        (:loading? cached) "Loading..."
        (:error? cached) "Tweet unavailable"
        :else (str "Tweet #" tweet-id))]
     [:div.tweet-embed-footer
      "Click to view on X →"]]))

(defn- video-embed
  "Render a video embed preview."
  [{:keys [block-id url is-focused on-intent db]}]
  (let [view-key (str block-id "-video")
        click-handler {:on {:click (fn [e]
                                     (.stopPropagation e)
                                     (cond
                                       (.-shiftKey e)
                                       (shift-click-select-range! db block-id on-intent)

                                       is-focused
                                       (on-intent {:type :enter-edit :block-id block-id})

                                       :else
                                       (on-intent {:type :selection :mode :replace :ids block-id})))}}]
    [:div.video-embed
     (merge {:replicant/key view-key} click-handler)
     [:div.video-embed-header
      [:span.video-icon "🎬"]
      [:span "Video"]]
     [:a.video-link {:href url :target "_blank" :rel "noopener noreferrer"}
      url]]))

(defn- view-content
  "View mode content: controlled rendering from DB with page-ref support.

   Detects markdown formatting:
   - '> ' prefix: renders as blockquote
   - '# '-'###### ' prefix: renders as h1-h6
   - {{tweet URL}}: renders as tweet embed preview
   - {{video URL}}: renders as video embed preview
   - Otherwise: renders as span

   In view mode, the prefix is hidden and content is styled appropriately.
   In edit mode (edit-content), the raw markdown is shown."
  [{:keys [block-id text is-focused on-intent db]}]
  (let [view-key (str block-id "-view")
        {:keys [format level content url]} (block-format/parse text)]

    ;; Special handling for embeds - they render their own container
    (case format
      :tweet (tweet-embed {:block-id block-id :url url :is-focused is-focused
                           :on-intent on-intent :db db})

      :video (video-embed {:block-id block-id :url url :is-focused is-focused
                           :on-intent on-intent :db db})

      ;; Default: quote, heading, or plain text
      (let [container-tag (case format
                            :quote :blockquote.block-content
                            :heading (keyword (str "h" level ".block-content"))
                            :span.block-content)
            click-handler {:on {:click (fn [e]
                                         (.stopPropagation e)
                                         (cond
                                           (.-shiftKey e)
                                           (shift-click-select-range! db block-id on-intent)

                                           is-focused
                                           (on-intent {:type :enter-edit :block-id block-id})

                                           :else
                                           (on-intent {:type :selection :mode :replace :ids block-id})))}}]
        (into [container-tag
               (merge {:replicant/key view-key} click-handler)]
              (render-text-with-page-refs db content on-intent))))))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn Block
  "Reusable block component with uncontrolled editing architecture.

   Props:
   - db: application database
   - block-id: ID of block to render
   - depth: nesting depth (for indentation)
   - is-focused: boolean, true if this block has focus (passed from parent)
   - is-selected: boolean, true if this block is in selection set (passed from parent)
   - is-editing: boolean, true if this block is in edit mode (passed from parent)
   - is-folded: boolean, true if this block is folded (passed from parent)
   - on-intent: callback for dispatching intents

   ARCHITECTURE:
   - View mode: Pure controlled rendering from DB
   - Edit mode: Uncontrolled - browser owns text, syncs to buffer on input"
  [{:keys [db block-id depth is-focused is-selected is-editing is-folded on-intent]
    :or {is-focused false is-selected false is-editing false is-folded false}}]
  (let [children (get-in db [:children-by-parent block-id] [])
        text (get-in db [:nodes block-id :props :text] "")

        ;; Drag visual feedback
        drop-style (drop-zone-style block-id)
        is-dragging (contains? (vs/dragging-ids) block-id)

        ;; Build CSS class vector (Replicant prefers vectors over space-separated strings)
        block-classes (cond-> ["block"]
                        is-focused (conj "focused")
                        is-selected (conj "selected")
                        is-editing (conj "editing")
                        is-dragging (conj "dragging"))

        container-props
        {:replicant/key block-id
         :class block-classes
         :data-block-id block-id
         :draggable true
         ;; No extra margin - hierarchy shown via border line only
         :style (merge {:opacity (if is-dragging 0.5 1)}
                       drop-style)
         :on {:click (fn [e]
                       (.stopPropagation e)
                       (if (.-shiftKey e)
                         (shift-click-select-range! db block-id on-intent)
                         (on-intent {:type :selection :mode :replace :ids block-id})))
              :dragstart (fn [e] (handle-drag-start e block-id))
              :dragenter (fn [e] (handle-drag-enter e block-id))
              :dragend handle-drag-end
              :dragover (fn [e] (handle-drag-over e block-id))
              :dragleave handle-drag-leave
              :drop (fn [e] (handle-drop e db block-id on-intent))}}

        ;; Fold indicator - integrated into block::before via CSS, but toggle still needed
        has-children? (seq (q/children db block-id))
        bullet [:span.block-bullet
                {:class (cond
                          (not has-children?) "bullet-dot"
                          is-folded "bullet-collapsed"
                          :else "bullet-expanded")
                 :on {:click (fn [e]
                               (.stopPropagation e)
                               (when has-children?
                                 (if (.-altKey e)
                                   (on-intent {:type :toggle-subtree :block-id block-id})
                                   (on-intent {:type :toggle-fold :block-id block-id}))))}}]

        ;; Content: delegate to mode-specific helpers
        content (if is-editing
                  (edit-content {:block-id block-id :text text :on-intent on-intent :db db})
                  (view-content {:block-id block-id :text text :is-focused is-focused :on-intent on-intent :db db}))

        ;; Autocomplete popup - only show when editing this block and autocomplete is active for it
        autocomplete-state (vs/autocomplete)
        show-autocomplete? (and is-editing
                                autocomplete-state
                                (= (:block-id autocomplete-state) block-id))

        ;; Session state for children (computed once per render)
        editing-block-id (vs/editing-block-id)
        focus-block-id (vs/focus-id)
        selection-set (vs/selection-nodes)
        folded-set (vs/folded)

        children-el
        (when (seq children)
          (into [:div.block-children]
                (map (fn [child-id]
                       (Block {:db db
                               :block-id child-id
                               :depth (inc depth)
                               :is-focused (= focus-block-id child-id)
                               :is-selected (contains? selection-set child-id)
                               :is-editing (= editing-block-id child-id)
                               :is-folded (contains? folded-set child-id)
                               :on-intent on-intent}))
                     children)))]

    (cond-> [:div container-props
             bullet
             content
             ;; Render autocomplete popup when active for this block
             (when show-autocomplete?
               (autocomplete/Popup
                {:autocomplete autocomplete-state
                 :on-select #(on-intent {:type :autocomplete/select})
                 :on-dismiss #(on-intent {:type :autocomplete/dismiss})}))]
      ;; Only show children if not folded
      (and children-el (not is-folded)) (conj children-el))))
