(ns components.block
  "Block component with uncontrolled contenteditable architecture.

   ARCHITECTURE: Uncontrolled editing - browser owns text state during edit mode.
   - View mode: Controlled by DB (text rendered from DB)
   - Edit mode: Uncontrolled (browser manages text, syncs to buffer, commits on blur)
   - Buffer: High-velocity keystroke storage (no history/indexing overhead)"
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [parser.block-format :as block-format]
            [parser.inline-format :as inline-format]
            [parser.markdown-links :as md-links]
            [parser.images :as images]
            [parser.parse :as parse]
            [components.image :as image]
            [shell.render-registry :as render-registry]
            ;; load-time handler registration — shell.editor already
            ;; requires this, repeated here for local clarity
            [shell.render-manifest]
            [components.autocomplete :as autocomplete]
            [utils.text-selection :as text-sel]
            [utils.cursor-boundaries :as bounds]
            [utils.intent-helpers :as intent-helpers]
            [utils.block-dom :as block-dom]
            [utils.dom :as dom]
            [utils.html-to-markdown :as html-md]
            [utils.image :as img-util]
            [shell.view-state :as vs]
            [shell.storage :as storage]
            [clojure.string :as str]))

;; Forward declarations for functions used in drag/drop before definition
(declare get-image-files upload-and-insert-images!)

;; ── MathJax Integration ──────────────────────────────────────────────────────
;; Debounced typeset to avoid excessive re-rendering during typing

(defonce ^:private mathjax-timeout (atom nil))

(defn- typeset-math!
  "Trigger MathJax to typeset any .math elements. Debounced to 100ms."
  []
  (when-let [timeout @mathjax-timeout]
    (js/clearTimeout timeout))
  (reset! mathjax-timeout
          (js/setTimeout
           (fn []
             (when (and js/window.MathJax js/window.MathJax.typesetPromise)
               (.typesetPromise js/window.MathJax)))
           100)))

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
;; MOVED: detect-cursor-row-position → utils/cursor_boundaries.cljs
;; Now computed ONCE per keydown via bounds/boundary-state

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

(defn- apply-edit!
  "Apply text edit to DOM, buffer, and position cursor atomically.

   Use for autocomplete insertions, paste, image upload - any edit
   that needs DOM + buffer + cursor in sync.

   Args:
   - node: contenteditable DOM element
   - block-id: block being edited
   - text: new text content
   - cursor-pos: cursor position after edit
   - html?: if true, use innerHTML (for text with newlines needing <br>)"
  ([node block-id text cursor-pos]
   (apply-edit! node block-id text cursor-pos false))
  ([node block-id text cursor-pos html?]
   (if html?
     (set! (.-innerHTML node) (text->html text))
     (set! (.-textContent node) text))
   (vs/buffer-set! block-id text)
   (set-cursor! node cursor-pos)))

(defn- commit-edit!
  "Commit current edit state to DB.

   Buffer-first pattern: prefers buffer text (more reliable in tests),
   falls back to DOM. Guards against stale commits when block changed.

   Returns: final text (for operations needing it), or nil if skipped."
  [block-id target on-intent]
  (let [buffer-text (vs/buffer-text block-id)
        dom-text (when target (extract-text-with-newlines target))
        final-text (or buffer-text dom-text)
        same-block? (= (vs/editing-block-id) block-id)]
    ;; Dismiss autocomplete if active
    (when (vs/autocomplete-active?)
      (on-intent {:type :autocomplete/dismiss}))
    ;; Only commit if still editing this block
    (when (and final-text same-block?)
      (on-intent {:type :update-content
                  :block-id block-id
                  :text final-text})
      final-text)))

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
  "Execute move on drop, or upload images if files are dropped."
  [e db block-id on-intent]
  (.preventDefault e)
  (.stopPropagation e)
  (let [data-transfer (.-dataTransfer e)
        files (.-files data-transfer)
        image-files (get-image-files files)]
    ;; FILE DROP: If dropping image files, upload them and insert markdown
    (if (seq image-files)
      (do
        ;; Upload and create image blocks after this block
        (upload-and-insert-images! image-files nil nil block-id on-intent)
        ;; Clear any drag state
        (vs/drag-end!))

      ;; BLOCK MOVE: Existing drag-and-drop block reordering logic
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
                        :anchor anchor})))))))

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

   REFACTORED: Now receives pre-computed boundaries from handle-keydown.

   CROSS-PAGE NAVIGATION: In JournalsView, blocks from different pages
   are rendered together. When at a page boundary, we provide a DOM-based
   fallback block ID so navigation can cross page boundaries seamlessly.

   Args:
   - direction: :up or :down
   - e, _db, block-id, on-intent: standard handler args
   - cursor-bounds: pre-computed boundary state from bounds/boundary-state"
  [direction e _db block-id on-intent cursor-bounds]
  (let [[boundary-key collapse-fn]
        (if (= direction :up)
          [:first-row? collapse-selection-start!]
          [:last-row? collapse-selection-end!])]
    (cond
      ;; Has text selection - collapse appropriately
      (:has-selection? cursor-bounds)
      (collapse-fn e)

      ;; At boundary row - navigate to adjacent block with cursor memory
      (get cursor-bounds boundary-key)
      (do (.preventDefault e)
          ;; In journals view, provide DOM-adjacent fallback for cross-page nav
          (let [dom-fallback (when (vs/journals-view?)
                               (block-dom/get-adjacent-block-by-dom direction block-id))]
            (on-intent (intent-helpers/navigate-with-cursor-memory-intent
                        {:direction direction
                         :block-id block-id
                         :current-text (:text-content cursor-bounds)
                         :current-cursor-pos (:cursor-pos cursor-bounds)
                         :dom-adjacent-id dom-fallback}))))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-arrow-up
  "Handle ArrowUp: navigate to previous block if at first row."
  [e db block-id on-intent cursor-bounds]
  (handle-vertical-arrow :up e db block-id on-intent cursor-bounds))

(defn handle-arrow-down
  "Handle ArrowDown: navigate to next block if at last row."
  [e db block-id on-intent cursor-bounds]
  (handle-vertical-arrow :down e db block-id on-intent cursor-bounds))

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

   REFACTORED: Now receives pre-computed boundaries from handle-keydown.
   Eliminated ~15 lines of inline at-start? detection.

   Behaviors:
   - Has text selection → Collapse to start
   - At start of block → Edit previous block at end
   - Middle of text → Move cursor left (browser default)"
  [e _db block-id on-intent cursor-bounds]
  (cond
    ;; Has selection - collapse to start
    (:has-selection? cursor-bounds)
    (collapse-selection-start! e)

    ;; At start - navigate to previous block
    (:at-start? cursor-bounds)
    (do (.preventDefault e)
        (on-intent (intent-helpers/navigate-to-adjacent-intent
                    {:direction :up
                     :block-id block-id
                     :cursor-position :max}))) ; Enter previous at end

    ;; Middle - let browser handle
    :else nil))

(defn handle-arrow-right
  "Handle right arrow key.

   REFACTORED: Now receives pre-computed boundaries from handle-keydown.

   Behaviors:
   - Has text selection → Collapse to end
   - At end of block → Edit next block at start
   - Middle of text → Move cursor right (browser default)"
  [e _db block-id on-intent cursor-bounds]
  (cond
    ;; Has selection - collapse to end
    (:has-selection? cursor-bounds)
    (collapse-selection-end! e)

    ;; At end - navigate to next block
    (:at-end? cursor-bounds)
    (do (.preventDefault e)
        (on-intent (intent-helpers/navigate-to-adjacent-intent
                    {:direction :down
                     :block-id block-id
                     :cursor-position 0}))) ; Enter next at start

    ;; Middle - let browser handle
    :else nil))

(defn handle-enter [e db block-id on-intent]
  (.preventDefault e)
  (let [target (.-target e)
        ;; Get DOM text with proper newline handling (BR → \n)
        dom-text (extract-text-with-newlines target)
        ;; Get cursor position that matches DOM text (includes BRs as \n)
        cursor-pos (or (:position (text-sel/get-position target)) 0)
        ;; LOGSEQ PARITY: Empty block auto-outdent
        ;; Conditions: empty content + no next sibling + not at top level
        is-empty? (str/blank? dom-text)
        has-next? (some? (q/next-sibling db block-id))
        parent (q/parent-of db block-id)
        ;; "At root" = parent is :doc OR parent is a page (blocks directly under pages
        ;; shouldn't auto-outdent since there's nowhere to outdent TO)
        parent-is-page? (= :page (get-in db [:nodes parent :type]))
        at-root? (or (= parent :doc) parent-is-page?)]
    (if (and is-empty? (not has-next?) (not at-root?))
      ;; Auto-outdent: move block to be sibling of parent
      ;; Use :outdent-selected which preserves editing state
      (on-intent {:type :outdent-selected})
      ;; Normal: create new block via context-aware-enter
      ;; CRITICAL: Pass pending-buffer with DOM text so split uses same text
      ;; that cursor position was calculated against (fixes multiline split)
      (on-intent {:type :context-aware-enter
                  :block-id block-id
                  :cursor-pos cursor-pos
                  :pending-buffer {:block-id block-id :text dom-text}}))))

(defn handle-escape [e _db block-id on-intent]
  (.preventDefault e)
  (.stopPropagation e) ; Prevent global keydown from also handling Escape
  (commit-edit! block-id (.-target e) on-intent)
  (on-intent {:type :exit-edit-and-select :block-id block-id}))

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

(defn- autocomplete-arrow-navigation
  "Handle up/down arrow navigation in autocomplete list."
  [e direction on-intent]
  (.preventDefault e)
  (.stopPropagation e)
  (on-intent {:type :autocomplete/navigate :direction direction})
  true)

(defn- autocomplete-arrow-left
  "Close autocomplete if cursor moves before trigger position."
  [autocomplete-state on-intent]
  (let [{:keys [type trigger-length trigger-pos]} autocomplete-state
        trig-len (or trigger-length (if (= type :command) 1 2))
        selection (.getSelection js/window)
        cursor-pos (when selection (.-anchorOffset selection))
        ;; For page-ref: trigger-pos is after [[, so [[ starts at trigger-pos - 2
        ;; For command: trigger-pos is after /, so / starts at trigger-pos - 1
        trigger-start (- trigger-pos trig-len)
        at-or-before-trigger? (and cursor-pos (<= cursor-pos trigger-start))]
    (when at-or-before-trigger?
      (on-intent {:type :autocomplete/dismiss}))
    false))

(defn- autocomplete-arrow-right
  "Close autocomplete if cursor moves past the region.
   For page-ref: past ]]
   For command: past word boundary (space or end of text)"
  [e autocomplete-state on-intent]
  (let [{:keys [type trigger-pos]} autocomplete-state
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
    false))

(defn- autocomplete-backspace
  "Delete trigger characters if at trigger position.
   For page-ref: delete both [[ and ]]
   For command: delete just /"
  [e autocomplete-state on-intent]
  (let [{:keys [type trigger-pos query block-id]} autocomplete-state
        target (.-target e)
        text (.-textContent target)
        selection (.getSelection js/window)
        cursor-pos (when selection (.-anchorOffset selection))
        ;; Check if cursor is right after trigger with empty query
        at-trigger? (and cursor-pos (= cursor-pos trigger-pos) (empty? query))]
    (if at-trigger?
      (if (= type :command)
        ;; Command mode: just delete the /
        (let [start-pos (dec trigger-pos)
              new-text (str (subs text 0 start-pos)
                            (subs text trigger-pos))
              new-cursor start-pos]
          (.preventDefault e)
          (.stopPropagation e)
          (apply-edit! target block-id new-text new-cursor)
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
          (apply-edit! target block-id new-text new-cursor)
          (on-intent {:type :autocomplete/dismiss})
          true))
      ;; Not at trigger - let backspace through
      false)))

(defn- insert-command-completion
  "Insert command completion text and update cursor."
  [target block-id text trigger-pos query item on-intent]
  (let [start-pos (dec trigger-pos)
        end-pos (+ trigger-pos (count query))
        insert-str ((:insert-fn item))
        item-backward (get item :backward-pos 0)
        new-text (str (subs text 0 start-pos)
                      insert-str
                      (subs text (min end-pos (count text))))
        new-cursor (- (+ start-pos (count insert-str)) item-backward)]
    (apply-edit! target block-id new-text new-cursor)
    (on-intent {:type :autocomplete/dismiss})
    (on-intent {:type :update-content
                :block-id block-id
                :text new-text})))

(defn- insert-page-ref-completion
  "Insert page-ref completion text and update cursor."
  [target block-id text trigger-pos query item on-intent]
  (let [start-pos (- trigger-pos 2)
        end-pos (+ trigger-pos (count query) 2)
        page-title (:title item)
        insert-str (str "[[" page-title "]]")
        new-text (str (subs text 0 start-pos)
                      insert-str
                      (subs text (min end-pos (count text))))
        new-cursor (+ start-pos (count insert-str))
        is-create-new? (= (:type item) :create-new)]
    (apply-edit! target block-id new-text new-cursor)
    (on-intent {:type :autocomplete/dismiss})
    (when is-create-new?
      (on-intent {:type :page/create
                  :title page-title
                  :navigate? false}))
    (on-intent {:type :update-content
                :block-id block-id
                :text new-text})))

(defn- autocomplete-selection
  "Handle Enter/Tab to select current autocomplete item.
   Stays in edit mode (Logseq parity)."
  [e autocomplete-state on-intent]
  (.preventDefault e)
  (.stopPropagation e)
  (let [{:keys [type trigger-pos query selected items block-id]} autocomplete-state
        item (get items selected)]
    (when item
      (let [target (.-target e)
            text (.-textContent target)]
        (if (= type :command)
          (insert-command-completion target block-id text trigger-pos query item on-intent)
          (insert-page-ref-completion target block-id text trigger-pos query item on-intent)))))
  true)

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
      (cond
        (= key "ArrowUp")
        (autocomplete-arrow-navigation e :up on-intent)

        (= key "ArrowDown")
        (autocomplete-arrow-navigation e :down on-intent)

        (= key "ArrowLeft")
        (autocomplete-arrow-left autocomplete-state on-intent)

        (= key "ArrowRight")
        (autocomplete-arrow-right e autocomplete-state on-intent)

        (= key "Backspace")
        (autocomplete-backspace e autocomplete-state on-intent)

        (or (= key "Enter") (= key "Tab"))
        (autocomplete-selection e autocomplete-state on-intent)

        ;; Home/End - dismiss and let cursor move
        (or (= key "Home") (= key "End"))
        (do (on-intent {:type :autocomplete/dismiss})
            false)

        (= key "Escape")
        (do (.preventDefault e)
            (.stopPropagation e)
            (on-intent {:type :autocomplete/dismiss})
            true)

        :else false))))

(defn- autocomplete-region
  "Compute query region boundaries for active autocomplete.

   Returns map with:
   - :query-end - End position of query region
   - :trigger-valid? - Whether trigger marker is present in text
   - :cursor-past-end? - Whether cursor moved past query end (page-ref only)

   Command mode: query ends at space/newline or text end, checks for / trigger
   Page-ref mode: query ends at ]], checks for [[ trigger, tracks cursor past close"
  [text trigger-pos cursor-pos type]
  (if (= type :command)
    ;; Command mode: find space/newline boundary, verify / trigger
    (let [query-end (or (str/index-of text " " trigger-pos)
                        (str/index-of text "\n" trigger-pos)
                        (count text))
          trigger-valid? (and (>= trigger-pos 1)
                              (= (subs text (dec trigger-pos) trigger-pos) "/"))]
      {:query-end query-end
       :trigger-valid? trigger-valid?
       :cursor-past-end? false})

    ;; Page-ref mode: find ]] boundary, verify [[ trigger, check cursor past close
    (let [close-bracket-pos (str/index-of text "]]" trigger-pos)
          query-end (or close-bracket-pos (count text))
          trigger-valid? (and (>= trigger-pos 2)
                              (= (subs text (- trigger-pos 2) trigger-pos) "[["))
          cursor-past-end? (and close-bracket-pos
                                (> cursor-pos (+ close-bracket-pos 2)))]
      {:query-end query-end
       :trigger-valid? trigger-valid?
       :cursor-past-end? cursor-past-end?})))

(defn- handle-active-autocomplete
  "Handle autocomplete query updates when autocomplete is already active.

   Updates query as user types within valid region, dismisses if cursor leaves region."
  [text cursor-pos trigger-pos type on-intent]
  (let [{:keys [query-end trigger-valid? cursor-past-end?]}
        (autocomplete-region text trigger-pos cursor-pos type)

        in-region? (and (>= cursor-pos trigger-pos)
                        (<= cursor-pos query-end))

        valid? (and trigger-valid? in-region? (not cursor-past-end?))]

    (if valid?
      (let [new-query (subs text trigger-pos (min cursor-pos query-end))]
        (on-intent {:type :autocomplete/update :query new-query}))
      (on-intent {:type :autocomplete/dismiss}))))

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
        (handle-active-autocomplete text cursor-pos trigger-pos type on-intent))

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

;; ── Key Matching Helpers ─────────────────────────────────────────────────────

(defn- key-match?
  "Match key with required modifiers and no extra modifiers.

   Examples:
   - (key-match? \"a\" {:ctrl? true} mods) - Ctrl+A only
   - (key-match? \"ArrowUp\" {:shift? true} mods) - Shift+ArrowUp only
   - (key-match? \"Escape\" {} mods) - Escape with no modifiers"
  [expected-key required-mods {:keys [shift? mod? alt? ctrl?] :as actual-mods}]
  (and (= expected-key (:key actual-mods))
       (= (:shift? required-mods false) shift?)
       (= (:mod? required-mods false) mod?)
       (= (:alt? required-mods false) alt?)
       (= (:ctrl? required-mods false) ctrl?)))

(defn- plain-key?
  "Match key with no modifiers (shift, mod, alt, ctrl all false)."
  [expected-key mods]
  (key-match? expected-key {} mods))

(defn- find-line-start
  "Find start of current line (position after last newline before cursor)."
  [text-content cursor-pos]
  (loop [pos (dec cursor-pos)]
    (cond
      (< pos 0) 0
      (= (nth text-content pos) \newline) (inc pos)
      :else (recur (dec pos)))))

(defn- find-line-end
  "Find end of current line (position of next newline or end of text)."
  [text-content cursor-pos text-length]
  (loop [pos cursor-pos]
    (cond
      (>= pos text-length) text-length
      (= (nth text-content pos) \newline) pos
      :else (recur (inc pos)))))

(defn- handle-enter-key
  "Handle Enter key based on document view mode and shift modifier."
  [e db block-id on-intent target shift?]
  (if (vs/document-view?)
    ;; Doc-mode: Enter=newline, Shift+Enter=new block
    (if shift?
      (handle-enter e db block-id on-intent)
      (insert-linebreak! e target block-id))
    ;; Normal mode: Enter=new block, Shift+Enter=newline
    (if shift?
      (insert-linebreak! e target block-id)
      (handle-enter e db block-id on-intent))))

;; ── Main Keydown Handler ─────────────────────────────────────────────────────

(defn handle-keydown
  "Handle keyboard events while editing a block.

   REFACTORED ARCHITECTURE:
   1. Compute cursor boundaries ONCE at entry (bounds/boundary-state)
   2. IME guard: skip all interception during composition (CJK/emoji safety)
   3. Pass pre-computed bounds to handlers (no inline DOM reads)
   4. Use key-match? helpers to reduce duplication in modifier checking

   Delegates to editing handlers for navigation, splits, and escapes.
   When autocomplete is active, intercepts navigation keys."
  [e db block-id on-intent]
  ;; Check autocomplete first - it intercepts arrow keys, Enter, Escape, Tab
  (when-not (handle-autocomplete-keydown e on-intent)
    (let [target (.-target e)
          ;; CRITICAL: Compute boundaries ONCE per keydown
          ;; This consolidates ~40 lines of scattered boundary detection
          cursor-bounds (bounds/boundary-state target e)]

      ;; IME GUARD: Never intercept during composition (CJK, emoji, dictation)
      ;; This was completely missing before - major bug for international users
      (when-not (:is-composing? cursor-bounds)
        (let [mods (assoc (dom/event-modifiers e) :key (.-key e))
              {:keys [shift? alt?]} mods
              key (.-key e)]

          (cond
            ;; === Emacs Line Navigation (macOS: Ctrl+A/E) ===
            (key-match? "a" {:ctrl? true} mods)
            (do (.preventDefault e)
                (let [line-start (find-line-start (:text-content cursor-bounds)
                                                   (:cursor-pos cursor-bounds))]
                  (set-cursor! target line-start)))

            (key-match? "e" {:ctrl? true} mods)
            (do (.preventDefault e)
                (let [line-end (find-line-end (:text-content cursor-bounds)
                                               (:cursor-pos cursor-bounds)
                                               (:text-length cursor-bounds))]
                  (set-cursor! target line-end)))

            ;; === Emacs Block Navigation (macOS: Alt+A/E) ===
            (key-match? "a" {:alt? true} mods)
            (do (.preventDefault e)
                (set-cursor! target 0))

            (key-match? "e" {:alt? true} mods)
            (do (.preventDefault e)
                (set-cursor! target (:text-length cursor-bounds)))

            ;; === Shift+Arrow - exit edit and extend block selection ===
            (key-match? "ArrowUp" {:shift? true} mods)
            (handle-shift-arrow-up e db block-id on-intent)

            (key-match? "ArrowDown" {:shift? true} mods)
            (handle-shift-arrow-down e db block-id on-intent)

            ;; === Emacs-style navigation aliases (Ctrl+P/N) ===
            (key-match? "p" {:ctrl? true} mods)
            (handle-arrow-up e db block-id on-intent cursor-bounds)

            (key-match? "n" {:ctrl? true} mods)
            (handle-arrow-down e db block-id on-intent cursor-bounds)

            ;; === Plain arrows - navigate between blocks while editing ===
            (plain-key? "ArrowUp" mods)
            (handle-arrow-up e db block-id on-intent cursor-bounds)

            (plain-key? "ArrowDown" mods)
            (handle-arrow-down e db block-id on-intent cursor-bounds)

            (plain-key? "ArrowLeft" mods)
            (handle-arrow-left e db block-id on-intent cursor-bounds)

            (plain-key? "ArrowRight" mods)
            (handle-arrow-right e db block-id on-intent cursor-bounds)

            ;; === Enter/Shift+Enter (behavior depends on doc-mode) ===
            ;; LOGSEQ PARITY: Normal mode (Enter=block, Shift+Enter=newline)
            ;;                Doc-mode (Enter=newline, Shift+Enter=block)
            (and (= key "Enter") (not (:mod? mods)) (not alt?))
            (handle-enter-key e db block-id on-intent target shift?)

            ;; === Escape - exit edit mode ===
            (= key "Escape")
            (handle-escape e db block-id on-intent)

            ;; === Backspace - delete/merge at cursor boundary ===
            (plain-key? "Backspace" mods)
            (handle-backspace e db block-id on-intent)

            ;; === Delete - merge with next block ===
            (plain-key? "Delete" mods)
            (handle-delete e db block-id on-intent)

            :else nil))))))

;; ── Content Rendering — delegates to shell.render-registry ──────────────────
;;
;; Block text → `parser.parse/parse` AST → `render-registry/render-node`.
;; Individual tag handlers live under src/shell/render/*. This component
;; composes the AST root and the container; it no longer knows how to
;; render bold, links, or page refs.

(def ^:private evo-page-card-excerpt-limit 140)

(defn- truncate-preview
  "Collapse newlines/extra whitespace and trim preview text to a readable
   one-line excerpt."
  [text]
  (let [normalized (some-> (or text "")
                           (str/replace #"\s+" " ")
                           (str/trim))]
    (cond
      (str/blank? normalized) nil
      (<= (count normalized) evo-page-card-excerpt-limit) normalized
      :else (str (subs normalized 0 (dec evo-page-card-excerpt-limit)) "…"))))

(defn- first-page-excerpt
  "Use the first non-blank top-level block as a compact page preview."
  [db page-id]
  (->> (q/children db page-id)
       (map #(q/block-text db %))
       (keep truncate-preview)
       first))

(defn- evo-page-card
  "Render a compact preview card for a link-only `evo://page/...` block.

   Distinct from the inline `:link` handler — this wraps the ENTIRE block
   as a card with title/excerpt/status, because the link is the whole
   block's content."
  [db label page-name on-intent]
  (let [page-id (q/find-page-by-name db page-name)
        page-title (if page-id (q/page-title db page-id) page-name)
        excerpt (when page-id (first-page-excerpt db page-id))
        preview-meta (if page-id "Evo page" "Page not found yet")]
    [:div.evo-page-card
     [:a {:href (str "evo://page/" (js/encodeURIComponent page-name))
          :class ["evo-page-card-link"]
          :title (str "Open page: " page-name)
          :on {:click (fn [e]
                        (.preventDefault e)
                        (.stopPropagation e)
                        (when on-intent
                          (on-intent {:type :navigate-to-page
                                      :page-name page-name})))}}
      [:div.evo-page-card-label label]
      [:div.evo-page-card-title page-title]
      [:div.evo-page-card-meta preview-meta]
      (when excerpt
        [:div.evo-page-card-excerpt excerpt])]]))

(defn- render-block-content
  "Parse BLOCK TEXT as AST and render through the registry. Returns a
   vector of hiccup children ready to splat into the block's container
   (span/blockquote/h{1..6}). The :doc handler already emits ZWSP for
   empty content."
  [db block-id text on-intent]
  (render-registry/render-node
    (parse/parse text)
    {:on-intent on-intent
     :db db
     :block-id block-id}))

;; ── Image Paste/Drop Handling ────────────────────────────────────────────────

(defn- image-file?
  "Check if a file is an image type."
  [file]
  (when file
    (str/starts-with? (.-type file) "image/")))

(defn- get-image-files
  "Extract image files from a FileList."
  [file-list]
  (when (and file-list (pos? (.-length file-list)))
    (->> (range (.-length file-list))
         (map #(aget file-list %))
         (filter image-file?))))

(defn- upload-and-insert-images!
  "Upload image files to assets folder and insert as markdown image blocks.

   Processing pipeline:
   1. Fix EXIF orientation (auto-rotate camera photos)
   2. Extract dimensions (for aspect ratio preservation)
   3. Generate content-hash filename (deduplication)
   4. Write to assets folder
   5. Create text blocks with ![alt](path){width=N} markdown

   Creates separate blocks for each uploaded file, inserted after
   the current block. Focus moves to the last inserted block in edit mode.

   Shows upload progress via toast notifications."
  [files _target _cursor-pos block-id on-intent]
  (js/console.log "📷 upload-and-insert-images! called"
                  (clj->js {:fileCount (count files)
                            :blockId block-id}))
  (when (seq files)
    (if-not (storage/has-folder?)
      ;; No folder open - show helpful message
      (vs/show-notification! "Open a folder first to save images"
                             {:type :warning :timeout 4000})
      ;; Folder open - proceed with upload
      (let [file-count (count files)
            upload-msg (if (= 1 file-count)
                         "Uploading image..."
                         (str "Uploading " file-count " images..."))]
        (js/console.log "📷 Starting upload of" file-count "files")
        ;; Show upload progress notification (long timeout)
        (vs/show-notification! upload-msg {:type :info :timeout 30000})
        (-> (js/Promise.all
             (to-array
              (map (fn [file]
                     ;; 1. Process image (EXIF fix + dimensions)
                     (-> (img-util/process-image-for-upload file)
                         (.then (fn [{:keys [blob width height]}]
                                  (js/console.log "📷 Processed image:" (.-name file)
                                                  "dimensions:" width "x" height)
                                  ;; 2. Generate hash-based filename
                                  (-> (storage/generate-asset-filename-with-hash (.-name file) blob)
                                      (.then (fn [filename]
                                               (js/console.log "📷 Writing asset:" filename)
                                               ;; 3. Write to storage
                                               (-> (storage/write-asset! filename blob)
                                                   (.then (fn [path]
                                                            (js/console.log "📷 Asset written, path:" path)
                                                            (when path
                                                              {:path path
                                                               :alt (.-name file)
                                                               :width width
                                                               :height height})))))))))))
                   files)))
            (.then (fn [image-data-js]
                     (let [images (->> (js->clj image-data-js :keywordize-keys true)
                                       (filter some?))
                           success-count (count images)]
                       (js/console.log "📷 All assets written, images:" (pr-str images))
                       ;; Show success notification
                       (if (pos? success-count)
                         (do
                           (vs/show-notification!
                            (if (= 1 success-count)
                              "Image uploaded"
                              (str success-count " images uploaded"))
                            {:type :success :timeout 2000})
                           ;; Fire intent to create image blocks
                           (js/console.log "📷 Firing intent :insert-image-blocks")
                           (on-intent {:type :insert-image-blocks
                                       :after-id block-id
                                       :images (vec images)}))
                         ;; All uploads failed
                         (vs/show-notification! "Upload failed" {:type :error :timeout 3000})))))
            (.catch (fn [err]
                      (js/console.error "📷 Failed to upload images:" err)
                      (vs/show-notification! "Upload failed" {:type :error :timeout 3000}))))))))

;; ── Content Mode Helpers ──────────────────────────────────────────────────────

(defn- edit-content
  "Edit mode content: uncontrolled contenteditable.

   Browser owns text state. Syncs to buffer on input, commits on blur.
   Lifecycle hooks handle focus and cursor positioning.

   NOTE: .math-ignore class tells MathJax to skip this element.
   Without it, MathJax would process $...$ patterns and strip delimiters."
  [{:keys [block-id text on-intent db]}]
  (let [edit-key (str block-id "-edit")]
    [:span.block-content.math-ignore.editing
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
          (dom/scroll-into-view! node)

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
          (dom/scroll-into-view! node))

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
                   (commit-edit! block-id (.-target e) on-intent)
                   (when-not (vs/keep-edit-on-blur?)
                     (on-intent {:type :exit-edit})))

           ;; Keydown: Keyboard shortcuts
           :keydown (fn [e]
                      (handle-keydown e db block-id on-intent))

           ;; Paste: Multi-paragraph splitting (Logseq parity)
           ;; CRITICAL: For simple inline paste, update DOM/buffer/cursor directly.
           ;; For multi-block paste (markdown/blank lines), let the intent handle it.
           ;; INTERNAL FORMAT: When pasting from our own copy/cut, use clipboard-blocks
           ;; which preserves exact hierarchy (siblings stay siblings).
           ;; HTML CONVERSION: If text/html is richer than text/plain, convert via turndown.
           :paste (fn [e]
                    (.preventDefault e)
                    (let [target (.-target e)
                          clipboard-data (.-clipboardData e)
                          ;; Check for image files FIRST
                          files (.-files clipboard-data)
                          image-files (get-image-files files)
                          selection (.getSelection js/window)
                          anchor-offset (.-anchorOffset selection)
                          focus-offset (.-focusOffset selection)
                          selection-start (min anchor-offset focus-offset)]

                      ;; IMAGE PASTE: If clipboard has image files, upload them
                      (if (seq image-files)
                        (upload-and-insert-images! image-files target selection-start block-id on-intent)

                        ;; TEXT PASTE: Fall through to existing text handling
                        (let [plain-text (.getData clipboard-data "text/plain")
                              html-text (.getData clipboard-data "text/html")
                              ;; HTML CONVERSION: Use converted HTML if it adds formatting value
                              pasted-text (if (html-md/better-than-plain? html-text plain-text)
                                            (html-md/convert html-text)
                                            plain-text)
                              selection-end (max anchor-offset focus-offset)
                              ;; Check if this paste matches our internal clipboard
                              internal-text (vs/clipboard-text)
                              internal-blocks (vs/clipboard-blocks)
                              use-internal? (and (seq internal-blocks)
                                                 (= plain-text internal-text))
                              ;; Detect multi-block paste (markdown or blank lines)
                              is-markdown? (boolean (re-find #"(?m)^\s*[-*+]\s+" pasted-text))
                              has-blank-lines? (boolean (re-find #"\n\n" pasted-text))
                              is-multi-block? (or use-internal? is-markdown? has-blank-lines?)
                              current-text (extract-text-with-newlines target)
                              ;; URL WRAP: bare URL + non-empty selection → wrap selection as [label](url).
                              ;; Runs BEFORE multi-block detection so URL-only clipboards take the
                              ;; wrap path, not paste-text. Only fires when not using internal clipboard.
                              trimmed-clip (str/trim plain-text)
                              bare-url? (boolean (re-matches #"^https?://\S+$" trimmed-clip))
                              has-selection? (< selection-start selection-end)
                              url-wrap? (and bare-url? has-selection? (not use-internal?))]

                          (cond
                            url-wrap?
                            ;; Wrap selection as markdown link: [selected](url)
                            (let [before (subs current-text 0 selection-start)
                                  selected (subs current-text selection-start selection-end)
                                  after (subs current-text selection-end)
                                  wrapped (str "[" selected "](" trimmed-clip ")")
                                  new-text (str before wrapped after)
                                  new-cursor-pos (+ selection-start (count wrapped))]
                              (apply-edit! target block-id new-text new-cursor-pos true)
                              (on-intent {:type :update-content
                                          :block-id block-id
                                          :text new-text}))

                            is-multi-block?
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

                            :else
                        ;; Simple inline paste: Update DOM/buffer directly for responsiveness
                            (let [before (subs current-text 0 selection-start)
                                  after (subs current-text selection-end)
                                  new-text (str before pasted-text after)
                                  new-cursor-pos (+ selection-start (count pasted-text))]
                              ;; Apply edit with html? = true (handles newlines as <br>)
                              (apply-edit! target block-id new-text new-cursor-pos true)
                              ;; Update DB
                              (on-intent {:type :paste-text
                                          :block-id block-id
                                          :cursor-pos selection-start
                                          :selection-end selection-end
                                          :pasted-text pasted-text})))))))}}]))

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
                                     (.preventDefault e)
                                     (cond
                                       (.-shiftKey e)
                                       (shift-click-select-range! db block-id on-intent)

                                       is-focused
                                       (on-intent {:type :enter-edit :block-id block-id})

                                       :else
                                       (on-intent {:type :selection :mode :replace :ids block-id})))}}
        ;; Use cached author name if available, fallback to username
        display-name (or (:author-name cached) (str "@" username) "@unknown")]
    [:div.tweet-embed
     (merge {:replicant/key view-key
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
      [:a {:href url :target "_blank" :rel "noopener noreferrer"
           :on {:click (fn [e] (.stopPropagation e))}}
       "View on X →"]]]))

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

(defn- image-only-block?
  "Check if text content is a single image with no other content.
   Returns the parsed image data {:path :alt :width} or nil."
  [text]
  (when (and text (images/image? text))
    (let [segments (images/split-with-images (str/trim text))]
      ;; Image-only if there's exactly one segment and it's an image
      (when (and (= 1 (count segments))
                 (= :image (:type (first segments))))
        (first segments)))))

(defn- image-block-content
  "Render an image-only block with resize handles when focused."
  [{:keys [block-id text is-focused on-intent db]}]
  (let [view-key (str block-id "-image")
        {:keys [path alt width]} (image-only-block? text)
        click-handler {:on {:click (fn [e]
                                     (.stopPropagation e)
                                     (cond
                                       (.-shiftKey e)
                                       (shift-click-select-range! db block-id on-intent)

                                       is-focused
                                       (on-intent {:type :enter-edit :block-id block-id})

                                       :else
                                       (on-intent {:type :selection :mode :replace :ids block-id})))}}]
    [:div.image-block-content
     (merge {:replicant/key view-key
             :class (when is-focused "focused")}
            click-handler)
     ;; Image with optional fixed width
     (image/Image {:path path
                   :alt alt
                   :width width
                   :block-level? true})
     ;; Resize handle (right edge) - only when focused
     (when is-focused
       [:div.image-resize-handle
        {:on {:mousedown (fn [e]
                           (image/start-resize! e block-id (or width 400) nil on-intent))}}])]))

(defn- view-content
  "View mode content: controlled rendering from DB with page-ref support.

   Detects markdown formatting:
   - ![alt](path){width=N}: renders as image-only block with resize handles
   - '> ' prefix: renders as blockquote
   - '# '-'###### ' prefix: renders as h1-h6
   - {{tweet URL}}: renders as tweet embed preview
   - {{video URL}}: renders as video embed preview
   - Otherwise: renders as span

   In view mode, the prefix is hidden and content is styled appropriately.
   In edit mode (edit-content), the raw markdown is shown."
  [{:keys [block-id text is-focused is-selected on-intent db]}]
  ;; Check for image-only block first (special handling with resize)
  (if (image-only-block? text)
    (image-block-content {:block-id block-id :text text :is-focused is-focused
                          :on-intent on-intent :db db})

    ;; Normal content rendering
    (let [view-key (str block-id "-view")
          {:keys [format level content url]} (block-format/parse text)]

      ;; Special handling for embeds - they render their own container
      (case format
        :tweet (tweet-embed {:block-id block-id :url url :is-focused is-focused
                             :on-intent on-intent :db db})

            :video (video-embed {:block-id block-id :url url :is-focused is-focused
                             :on-intent on-intent :db db})

        ;; Default: quote, heading, or plain text.
        ;; `.math-ignore` keeps MathJax from typesetting raw `$...$` runs in
        ;; normal prose (e.g. `cljs$core$key`, `price$100$total`). Explicit
        ;; math spans carry class `math` which `processHtmlClass` re-enables.
        (let [container-tag (case format
                              :quote :blockquote.block-content.math-ignore
                              :heading (keyword (str "h" level ".block-content.math-ignore"))
                              :span.block-content.math-ignore)
              click-handler {:on {:click (fn [e]
                                           (.stopPropagation e)
                                           (cond
                                             (.-shiftKey e)
                                             (shift-click-select-range! db block-id on-intent)

                                             is-focused
                                             (on-intent {:type :enter-edit :block-id block-id})

                                             :else
                                             (on-intent {:type :selection :mode :replace :ids block-id})))}}
              link-only (md-links/link-only? content)
              evo-link-target (some-> link-only :target md-links/parse-evo-target)
              ;; Special case: a block whose whole content is a single
              ;; evo://page/ link renders as a preview card. Every other
              ;; content flows through the render-registry pipeline and
              ;; the :doc handler takes care of ZWSP-on-empty.
              ;; :doc handler already emits ZWSP for empty content and
              ;; evo-page-card is always non-empty — no fallback needed.
              children (if (and link-only (= :page (:type evo-link-target)))
                         [(evo-page-card db
                                         (:label link-only)
                                         (:page-name evo-link-target)
                                         on-intent)]
                         (render-block-content db block-id content on-intent))
              ;; Delegate math detection to the parser — avoids
              ;; false-positive typeset passes on inputs like
              ;; `cljs$core$key` that the parser rejects.
              has-math? (inline-format/has-math? content)
              ;; Add on-render hook to typeset math when present
              ;; Row-scoped affordances live on .block-content, not on the
              ;; outer .block wrapper — otherwise the tint bleeds into
              ;; descendants that aren't focused/selected themselves.
              row-classes (cond-> []
                            is-focused (conj "focused")
                            is-selected (conj "selected"))
              container-props (cond-> (merge {:replicant/key view-key} click-handler)
                                (seq row-classes) (assoc :class row-classes)
                                has-math? (assoc :replicant/on-render
                                                 (fn [_] (typeset-math!))))]
          (into [container-tag container-props] children))))))

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
   - Edit mode: Uncontrolled - browser owns text, syncs to buffer on input

   BLOCK TYPES:
   - :block - Text content (default), images use markdown ![alt](path){width=N}
   - :embed - Video/tweet embeds"
  [{:keys [db block-id depth is-focused is-selected is-editing is-folded on-intent]
    :or {is-focused false is-selected false is-editing false is-folded false}}]
  (let [node (get-in db [:nodes block-id])
        block-type (get node :type :block)
        props (get node :props {})
        children (get-in db [:children-by-parent block-id] [])
        text (get props :text "")

        ;; Drag visual feedback
        drop-style (drop-zone-style block-id)
        is-dragging (contains? (vs/dragging-ids) block-id)

        ;; Build CSS class vector (Replicant prefers vectors over space-separated strings)
        block-classes (cond-> ["block"]
                        (= block-type :embed) (conj "embed-block")
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

        ;; Content: delegate to block-type-specific helpers
        content (case block-type
                  ;; Embed block: video, tweet, etc.
                  :embed (let [url (get props :url)
                               embed-type (get props :embed-type)]
                           (case embed-type
                             :twitter (tweet-embed {:block-id block-id :url url :is-focused is-focused
                                                    :on-intent on-intent :db db})
                             (:youtube :vimeo) (video-embed {:block-id block-id :url url :is-focused is-focused
                                                              :on-intent on-intent :db db})
                             ;; Fallback: just show URL as link
                             [:a.embed-fallback {:href url :target "_blank" :rel "noopener noreferrer"} url]))
                  ;; Default: text block with edit/view modes
                  (if is-editing
                    (edit-content {:block-id block-id :text text :on-intent on-intent :db db})
                    (view-content {:block-id block-id :text text :is-focused is-focused :is-selected is-selected :on-intent on-intent :db db})))

        ;; Autocomplete popup - only show when editing text blocks
        autocomplete-state (vs/autocomplete)
        show-autocomplete? (and (= block-type :block)
                                is-editing
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
                 :on-select #(on-intent {:type :autocomplete/select})}))]
      ;; Only show children if not folded
      (and children-el (not is-folded)) (conj children-el))))
