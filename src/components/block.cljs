(ns components.block
  "Block component with uncontrolled contenteditable architecture.

   ARCHITECTURE: Uncontrolled editing - browser owns text state during edit mode.
   - View mode: Controlled by DB (text rendered from DB)
   - Edit mode: Uncontrolled (browser manages text, syncs to buffer, commits on blur)
   - Buffer: High-velocity keystroke storage (no history/indexing overhead)"
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [kernel.constants :as const]
            [parser.page-refs :as page-refs]
            [components.page-ref :as page-ref]
            [util.text-selection :as text-sel]
            [shell.session :as session]))

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

(defn- has-text-selection?
  "Check if user has selected text within contenteditable."
  []
  (when-let [range (text-sel/get-current-range)]
    (not (.-collapsed range))))

(defn- ensure-block-selected!
  "Ensure the given block is the active selection anchor/focus before extending.

   When exiting edit mode via Shift+Arrow at a boundary, selection state is empty.
   We seed it with the editing block so :selection :mode :extend-*
   has the same anchor Logseq uses."
  [session block-id on-intent]
  (when-not (q/selected? session block-id)
    (on-intent {:type :selection :mode :replace :ids block-id})))

;; ── Keyboard handlers ─────────────────────────────────────────────────────────

(defn handle-arrow-up [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (cond
      ;; Has text selection - collapse to start
      (has-text-selection?)
      (let [selection (.getSelection js/window)]
        (.preventDefault e)
        (.collapseToStart selection))

      ;; At first row - navigate to previous block with cursor memory via Nexus
      (:first-row? cursor-pos)
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            ;; Emit Nexus action with required parameters
            (on-intent [[:editing/navigate-up {:block-id block-id
                                               :current-text text-content
                                               :current-cursor-pos cursor-offset
                                               :cursor-row :first}]])))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-arrow-down [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (cond
      ;; Has text selection - collapse to end
      (has-text-selection?)
      (let [selection (.getSelection js/window)]
        (.preventDefault e)
        (.collapseToEnd selection))

      ;; At last row - navigate to next block with cursor memory via Nexus
      (:last-row? cursor-pos)
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            ;; Emit Nexus action with required parameters
            (on-intent [[:editing/navigate-down {:block-id block-id
                                                 :current-text text-content
                                                 :current-cursor-pos cursor-offset
                                                 :cursor-row :last}]])))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-shift-arrow-up
  "Handle Shift+Up: text selection within block OR block selection at boundary.

   Logseq parity (LOGSEQ_SPEC.md §3):
   - NOT at first row → Let browser handle (text selection)
   - At first row → Exit edit mode, seed selection, extend upward"
  [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:first-row? cursor-pos)
      (.preventDefault e)
      ;; Collapse text selection before exiting
      (when-let [sel (.getSelection js/window)]
        (when (and sel (pos? (.-rangeCount sel)))
          (.collapseToStart sel)))
      ;; LOGSEQ PARITY (§3): Exit edit, select block, and extend upward atomically
      (on-intent {:type :exit-edit-and-extend :direction :prev}))))

(defn handle-shift-arrow-down
  "Handle Shift+Down: text selection within block OR block selection at boundary.

   Logseq parity (LOGSEQ_SPEC.md §3):
   - NOT at last row → Let browser handle (text selection)
   - At last row → Exit edit mode, seed selection, extend downward"
  [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:last-row? cursor-pos)
      (.preventDefault e)
      ;; Collapse text selection before exiting
      (when-let [sel (.getSelection js/window)]
        (when (and sel (pos? (.-rangeCount sel)))
          (.collapseToEnd sel)))
      ;; LOGSEQ PARITY (§3): Exit edit, select block, and extend downward atomically
      (on-intent {:type :exit-edit-and-extend :direction :next}))))

(defn handle-arrow-left
  "Handle left arrow key.

   Behaviors:
   - Has text selection → Collapse to start
   - At start of block → Edit previous block at end
   - Middle of text → Move cursor left (browser default)"
  [e db block-id on-intent]
  (let [target (.-target e)
        selection (.getSelection js/window)
        at-start? (and (= (.-anchorOffset selection) 0)
                       (= (.-anchorNode selection) (.-firstChild target)))]
    (cond
      ;; Has selection - collapse to start
      (has-text-selection?)
      (do (.preventDefault e)
          (.collapseToStart selection))

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
  [e db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        at-end? (= (.-anchorOffset selection) (count text-content))]
    (cond
      ;; Has selection - collapse to end
      (has-text-selection?)
      (do (.preventDefault e)
          (.collapseToEnd selection))

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
  (.log js/console "handle-enter called!" (clj->js {:block-id block-id}))
  (.preventDefault e)
  (let [selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)]
    (.log js/console "Dispatching smart-split" (clj->js {:cursor-pos cursor-pos}))
    ;; Emit Nexus action instead of intent
    (on-intent [[:editing/smart-split {:block-id block-id
                                       :cursor-pos cursor-pos}]])))

(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  ;; First, commit any unsaved content (blur might not fire reliably when unmounting)
  ;; Read from session buffer (more reliable than DOM textContent with Playwright)
  (let [buffer-text (get-in @session/!session [:buffer block-id])
        dom-text (.-textContent (.-target e))
        final-text (or buffer-text dom-text)]
    (when final-text
      (on-intent {:type :update-content
                  :block-id block-id
                  :text final-text})))
  ;; Then exit edit mode via Nexus action
  (on-intent [[:editing/escape {:block-id block-id}]]))

(defn handle-backspace [e db block-id on-intent]
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
        (on-intent {:type :merge-with-prev :block-id block-id})))))

(defn handle-delete
  "Handle Delete key - merge with next block if at end."
  [e db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        is-at-end (= (.-anchorOffset selection) (count text-content))]
    (when is-at-end
      (.preventDefault e)
      (on-intent {:type :merge-with-next :block-id block-id}))))

(defn handle-keydown
  "Handle keyboard events while editing a block.

   Delegates to editing handlers for navigation, splits, and escapes."
  [e db block-id on-intent]
  (let [key (.-key e)
        shift? (.-shiftKey e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        alt? (.-altKey e)
        ctrl? (.-ctrlKey e)
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
            (when-let [text-node (.-firstChild target)]
              (let [range (.createRange js/document)
                    sel (.getSelection js/window)]
                (.setStart range text-node line-start)
                (.setEnd range text-node line-start)
                (.removeAllRanges sel)
                (.addRange sel range)))))

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
            (when-let [text-node (.-firstChild target)]
              (let [range (.createRange js/document)
                    sel (.getSelection js/window)]
                (.setStart range text-node line-end)
                (.setEnd range text-node line-end)
                (.removeAllRanges sel)
                (.addRange sel range)))))

        ;; === Emacs Block Navigation (macOS: Alt+A/E) ===
      (and (= key "a") alt? (not shift?) (not ctrl?))
      (do (.preventDefault e)
          (when-let [text-node (.-firstChild target)]
            (let [range (.createRange js/document)
                  sel (.getSelection js/window)]
              (.setStart range text-node 0)
              (.setEnd range text-node 0)
              (.removeAllRanges sel)
              (.addRange sel range))))

      (and (= key "e") alt? (not shift?) (not ctrl?))
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                text-length (count text-content)]
            (when-let [text-node (.-firstChild target)]
              (let [range (.createRange js/document)
                    sel (.getSelection js/window)]
                (.setStart range text-node text-length)
                (.setEnd range text-node text-length)
                (.removeAllRanges sel)
                (.addRange sel range)))))

        ;; Shift+Arrow - text selection OR block selection at boundaries
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

        ;; LOGSEQ PARITY: Shift+Enter inserts literal newline
      (and (= key "Enter") shift? (not mod?) (not alt?))
      (do (.preventDefault e)
          (let [selection (.getSelection js/window)
                cursor-pos (.-anchorOffset selection)]
            (on-intent {:type :insert-newline
                        :block-id block-id
                        :cursor-pos cursor-pos})))

        ;; Enter - create new block
      (and (= key "Enter") (not shift?) (not mod?) (not alt?))
      (handle-enter e db block-id on-intent)

        ;; Escape - exit edit mode
      (= key "Escape")
      (handle-escape e db block-id on-intent)

        ;; Backspace - delete/merge at cursor boundary
      (and (= key "Backspace") (not shift?) (not mod?) (not alt?))
      (handle-backspace e db block-id on-intent)

        ;; Delete at end - merge with next block
      (and (= key "Delete") (not shift?) (not mod?) (not alt?))
      (handle-delete e db block-id on-intent)

      :else nil)))

;; ── Content Rendering with Page Refs ─────────────────────────────────────────

(defn- parse-page-refs
  "Parse text for page references [[page-name]].

   Returns vector of segments with :type and type-specific data.
   Types: :text, :page-ref"
  [text]
  (when text
    (let [page-ref-matches (map (fn [[match page]]
                                  {:type :page-ref
                                   :match match
                                   :page page
                                   :start (.indexOf text match)})
                                (page-refs/extract-refs text))
          all-matches (->> page-ref-matches
                           (filter #(>= (:start %) 0))
                           (sort-by :start))]

      ;; Build segments from matches
      (loop [remaining text
             matches-left all-matches
             result []]
        (if-let [match (first matches-left)]
          (let [idx (.indexOf remaining (:match match))
                before (subs remaining 0 idx)
                after (subs remaining (+ idx (count (:match match))))]
            (recur after
                   (rest matches-left)
                   (cond-> result
                     (not (empty? before)) (conj {:type :text :value before})
                     true (conj match))))
          ;; No more matches - add remaining text if any
          (cond-> result
            (not (empty? remaining)) (conj {:type :text :value remaining})))))))

(defn render-text-with-page-refs
  "Parse text for page references and render with PageRef components.

   Handles:
   - Page refs: [[page-name]] - page links

   Returns a seq of strings and components mixed together (NOT wrapped in a container).
   The parent component is responsible for providing the container."
  [db text on-intent]
  (let [segments (parse-page-refs text)]
    (map (fn [segment]
           (case (:type segment)
             :text (:value segment)
             :page-ref (page-ref/PageRef {:db db
                                          :page-name (:page segment)
                                          :on-intent on-intent})))
         segments)))

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
        selected? is-selected
        focus? is-focused
        editing? is-editing
        text (get-in db [:nodes block-id :props :text] "")

        container-props
        {:replicant/key block-id
         :data-block-id block-id
         :style {:margin-left (str (* depth 20) "px")
                 :padding "4px 8px"
                 :cursor "text"
                 :background-color (cond
                                     focus? "#b3d9ff"
                                     selected? "#e6f2ff"
                                     :else "transparent")
                 :border-left (if selected? "3px solid #0066cc" "3px solid #ccc")
                 :margin-bottom "2px"}
         :on {:click (fn [e]
                       (.stopPropagation e)
                       (if (.-shiftKey e)
                         ;; LOGSEQ PARITY (FR-Pointer-01): Shift+Click selects visible range
                         ;; Calculate range from anchor to clicked block (respects folding/zoom/page)
                         (let [session @shell.session/!session
                               anchor (get-in session [:selection :anchor])
                               ;; If no anchor, use current block as both anchor and end
                               range-ids (if anchor
                                           (q/visible-range db session anchor block-id)
                                           #{block-id})]
                           (on-intent {:type :selection :mode :extend :ids range-ids}))
                         (on-intent {:type :selection :mode :replace :ids block-id})))}}

        ;; Fold indicator bullet
        has-children? (seq (q/children db block-id))
        folded? is-folded
        bullet [:span {:style {:margin-right "8px"
                               :cursor (if has-children? "pointer" "default")
                               :user-select "none"}
                       :on {:click (fn [e]
                                     (.stopPropagation e)
                                     (when has-children?
                                       ;; LOGSEQ PARITY (FR-Pointer-01): Alt+Click toggles entire subtree
                                       (if (.-altKey e)
                                         (on-intent {:type :toggle-subtree :block-id block-id})
                                         (on-intent {:type :toggle-fold :block-id block-id}))))}}
                (cond
                  (not has-children?) "•"
                  folded? "▸"
                  :else "▾")]

        edit-key (str block-id "-edit")
        view-key (str block-id "-view")

        content
        (if editing?
          ;; === EDIT MODE: Uncontrolled ===
          [:span.content-edit
           {:contentEditable true
            :suppressContentEditableWarning true
            :style {:outline "none"
                    :min-width "1px"
                    :display "inline-block"}
            :data-block-id block-id
            :replicant/key edit-key

            ;; On mount: Set text once, focus, position cursor
            :replicant/on-mount
            (fn [{:replicant/keys [node]}]
              (let [initial-cursor (session/cursor-position)
                    cursor-pos (cond
                                 (number? initial-cursor) initial-cursor
                                 (= initial-cursor :start) 0
                                 (= initial-cursor :end) (count text)
                                 (= initial-cursor :max) (count text)
                                 :else (count text))]
                ;; Set text content (browser owns this from now on)
                (set! (.-textContent node) text)

                ;; EDGE CASE FIX: Empty blocks have no text node after setting textContent=""
                ;; Create an empty text node so cursor positioning works
                (when (zero? (.-length (.-childNodes node)))
                  (.appendChild node (.createTextNode js/document "")))

                ;; Focus UNCONDITIONALLY (required side effect, never conditional)
                (.focus node)

                ;; Position cursor - now safe because text node always exists
                (let [text-node (.-firstChild node)
                      range (.createRange js/document)
                      sel (.getSelection js/window)
                      safe-pos (min cursor-pos (count text))]
                  (.setStart range text-node safe-pos)
                  (.setEnd range text-node safe-pos)
                  (.removeAllRanges sel)
                  (.addRange sel range))))

            ;; On render: Maintain focus AND apply pending cursor position
            ;; LOGSEQ PARITY (FR-Undo-01): After undo/redo, cursor-position is set in session
            ;; We need to apply it to the DOM here since on-mount only fires on first render
            :replicant/on-render
            (fn [{:replicant/keys [node]}]
              ;; Focus if needed
              (when (not= (.-activeElement js/document) node)
                (.focus node))

              ;; Apply pending cursor position from session (set by undo/redo)
              (when-let [pending-cursor (session/cursor-position)]
                (let [text-content (.-textContent node)
                      text-length (count text-content)
                      cursor-pos (cond
                                   (number? pending-cursor) (min pending-cursor text-length)
                                   (= pending-cursor :start) 0
                                   (= pending-cursor :end) text-length
                                   (= pending-cursor :max) text-length
                                   :else nil)]
                  (when (and cursor-pos (.-firstChild node))
                    (let [text-node (.-firstChild node)
                          range (.createRange js/document)
                          sel (.getSelection js/window)
                          safe-pos (min cursor-pos text-length)]
                      (.setStart range text-node safe-pos)
                      (.setEnd range text-node safe-pos)
                      (.removeAllRanges sel)
                      (.addRange sel range)))
                  ;; Clear the pending cursor position to prevent reapplication
                  (session/swap-session! assoc-in [:ui :cursor-position] nil))))

            ;; Input handler: Update session directly (Phase 3: no intent dispatch)
            :on {:input (fn [e]
                          (let [target (.-target e)
                                new-text (.-textContent target)]
                            ;; Phase 3: Update session directly (instant, no dispatch overhead)
                            ;; NOTE: Use string block-id consistently (not keyword) to match DB convention
                            (session/swap-session! assoc-in [:buffer block-id] new-text)))

                 ;; Blur handler: Commit to canonical DB
                 :blur (fn [e]
                         (let [target (.-target e)
                               final-text (.-textContent target)
                               suppress? (session/suppress-blur-exit?)
                               same-block? (= (session/editing-block-id) block-id)]
                           ;; Commit to canonical DB
                           (on-intent {:type :update-content
                                       :block-id block-id
                                       :text final-text})
                           ;; Only exit edit mode if:
                           ;; 1. We're still editing THIS block
                           ;; 2. Not in a structural operation (indent/outdent/move)
                           ;; The suppress flag prevents blur from exiting during re-render
                           (when (and same-block? (not suppress?))
                             (on-intent {:type :exit-edit}))))

                 ;; Keydown: Keyboard shortcuts
                 :keydown (fn [e]
                            (handle-keydown e db block-id on-intent))

                 ;; Paste: Multi-paragraph splitting (Logseq parity)
                 :paste (fn [e]
                          (.preventDefault e)
                          (let [clipboard-data (.-clipboardData e)
                                pasted-text (.getData clipboard-data "text/plain")
                                selection (.getSelection js/window)
                                cursor-pos (.-anchorOffset selection)]
                            (on-intent {:type :paste-text
                                        :block-id block-id
                                        :cursor-pos cursor-pos
                                        :pasted-text pasted-text})))}}]

          ;; === VIEW MODE: Controlled ===
          [:span.content-view
           {:style {:min-width "1px"
                    :display "inline-block"
                    :cursor "text"}
            :data-block-id block-id
            :replicant/key view-key
            :replicant/on-render (fn [{:replicant/keys [node]}]
                                   (set! (.-textContent node) (or text "")))
            :on {:click (fn [e]
                          (.stopPropagation e)
                          ;; First click = select, second click (when focused) = enter edit mode
                          (if focus?
                            (on-intent {:type :enter-edit :block-id block-id})
                            (on-intent {:type :selection :mode :replace :ids block-id})))}}])

        ;; Session state for children (computed once per render)
        editing-block-id (session/editing-block-id)
        focus-block-id (session/focus-id)
        selection-set (session/selection-nodes)
        folded-set (session/folded)

        children-el
        (when (seq children)
          (into [:div {:style {:margin-top "2px"}}]
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

    (cond-> [:div.block container-props
             bullet
             content]
      ;; Only show children if not folded
      (and children-el (not folded?)) (conj children-el))))
