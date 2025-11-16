(ns components.block
  "Block component with Logseq-style editing behavior.

   Uses plugin getters for data and dispatches intents for all state changes.
   Implements cursor boundary detection for seamless up/down navigation."
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [parser.block-refs :as block-refs]
            [parser.embeds :as embeds]
            [parser.page-refs :as page-refs]
            [components.block-ref :as block-ref]
            [components.block-embed :as block-embed]
            [components.page-ref :as page-ref]))

;; ── Mock-text helpers (Logseq technique) ─────────────────────────────────────

(defn- update-mock-text!
  "Update hidden mock-text element with current contenteditable content and position.
   This enables cursor row position detection (Logseq technique).
   
   The mock-text element must be positioned at the same location as the editing
   block for accurate cursor row detection."
  [elem text]
  (when (and elem (.-getBoundingClientRect elem))
    (when-let [mock-elem (js/document.getElementById "mock-text")]
      ;; Position mock-text to match the editing element
      (let [rect (.getBoundingClientRect elem)
            top (.-top rect)
            left (.-left rect)
            width (.-width rect)]
        (set! (.. mock-elem -style -top) (str top "px"))
        (set! (.. mock-elem -style -left) (str left "px"))
        (set! (.. mock-elem -style -width) (str width "px")))

      ;; Update content with character spans
      (let [content (str text "0")
            chars (seq content)]
        (set! (.-innerHTML mock-elem) "")
        (doseq [[idx c] (map-indexed vector chars)]
          (let [span (.createElement js/document "span")]
            (.setAttribute span "id" (str "mock-text_" idx))
            (if (= c \newline)
              (do
                (set! (.-textContent span) "0")
                (.appendChild span (.createElement js/document "br")))
              (set! (.-textContent span) (str c)))
            (.appendChild mock-elem span)))))))

(defn- get-caret-rect
  "Get bounding rect of cursor position in contenteditable element."
  [elem]
  (when elem
    (let [selection (.getSelection js/window)]
      (when (and selection (> (.-rangeCount selection) 0))
        (let [range (.getRangeAt selection 0)
              rects (.getClientRects range)]
          (when (> (.-length rects) 0)
            (.item rects 0)))))))

(defn- get-mock-text-tops
  "Get unique Y positions (tops) of all characters in mock-text element."
  []
  (when-let [mock-elem (js/document.getElementById "mock-text")]
    (let [children (array-seq (.-children mock-elem))
          tops (->> children
                    (map (fn [span]
                           (let [rect (.getBoundingClientRect span)]
                             (.-top rect))))
                    distinct
                    sort)]
      tops)))

(defn- detect-cursor-row-position
  "Detect if cursor is on first/last row of contenteditable.
   Returns {:first-row? bool :last-row? bool}
   
   Uses character position in mock-text instead of range rect for accuracy
   with wrapped text."
  [elem]
  (when elem
    (let [selection (.getSelection js/window)]
      (when (and selection (> (.-rangeCount selection) 0))
        ;; Calculate cursor character index
        (let [char-index (loop [node (.createTreeWalker js/document elem 4 nil) ;; NodeFilter.SHOW_TEXT = 4
                                index 0]
                           (if-let [text-node (.nextNode node)]
                             (if (= text-node (.-focusNode selection))
                               (+ index (.-focusOffset selection))
                               (recur node (+ index (.-length text-node))))
                             index))

              ;; Get mock-text span for the character BEFORE cursor (the char cursor is after)
              mock-elem (js/document.getElementById "mock-text")
              mock-span-before (when (and mock-elem (pos? char-index))
                                 (aget (.-children mock-elem) (dec char-index)))

              ;; Get all unique line tops
              tops (get-mock-text-tops)

              ;; Cursor is on the same line as the character before it
              ;; (or first line if at position 0)
              cursor-top (if mock-span-before
                           (.-top (.getBoundingClientRect mock-span-before))
                           (first tops))]

          {:first-row? (and (seq tops) (= (first tops) cursor-top))
           :last-row? (and (seq tops) (= (last tops) cursor-top))})))))

(defn- has-text-selection?
  "Check if user has selected text within contenteditable."
  []
  (let [selection (.getSelection js/window)]
    (and selection
         (not (.-isCollapsed selection))
         (> (.-rangeCount selection) 0))))

(defn- ensure-block-selected!
  "Ensure the given block is the active selection anchor/focus before extending.

   When exiting edit mode via Shift+Arrow at a boundary, selection state is empty.
   We seed it with the editing block so :selection :mode :extend-*
   has the same anchor Logseq uses."
  [db block-id on-intent]
  (when-not (q/selected? db block-id)
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

(defn handle-shift-arrow-up [e db block-id on-intent]
  "Handle Shift+Up: text selection within block OR block selection at boundary.

   Logseq parity (§4.1):
   - NOT at first row → Let browser handle (text selection)
   - At first row → Collapse text selection to start, extend block selection upward"
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:first-row? cursor-pos)
      (.preventDefault e)
      ;; Collapse text selection to start before extending block selection
      (when-let [sel (.getSelection js/window)]
        (when (and sel (pos? (.-rangeCount sel)))
          (.collapseToStart sel)))
      ;; LOGSEQ PARITY §4.4: Seed selection with current block if empty
      ;; This prevents jumping to page top when extending from editing mode
      (ensure-block-selected! db block-id on-intent)
      ;; LOGSEQ PARITY: Incremental selection extension (with direction tracking)
      (on-intent {:type :selection :mode :extend-prev}))))

(defn handle-shift-arrow-down [e db block-id on-intent]
  "Handle Shift+Down: text selection within block OR block selection at boundary.

   Logseq parity (§4.1):
   - NOT at last row → Let browser handle (text selection)
   - At last row → Collapse text selection to end, extend block selection downward"
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:last-row? cursor-pos)
      (.preventDefault e)
      ;; Collapse text selection to end before extending block selection
      (when-let [sel (.getSelection js/window)]
        (when (and sel (pos? (.-rangeCount sel)))
          (.collapseToEnd sel)))
      ;; LOGSEQ PARITY §4.4: Seed selection with current block if empty
      ;; This prevents jumping to page bottom when extending from editing mode
      (ensure-block-selected! db block-id on-intent)
      ;; LOGSEQ PARITY: Incremental selection extension (with direction tracking)
      (on-intent {:type :selection :mode :extend-next}))))

(defn handle-arrow-left [e db block-id on-intent]
  "Handle left arrow key.

   Behaviors:
   - Has text selection → Collapse to start
   - At start of block → Edit previous block at end
   - Middle of text → Move cursor left (browser default)"
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

(defn handle-arrow-right [e db block-id on-intent]
  "Handle right arrow key.

   Behaviors:
   - Has text selection → Collapse to end
   - At end of block → Edit next block at start
   - Middle of text → Move cursor right (browser default)"
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
  (.preventDefault e)
  (let [selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)]
    ;; Emit Nexus action instead of intent
    (on-intent [[:editing/smart-split {:block-id block-id
                                       :cursor-pos cursor-pos}]])))

(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  ;; Emit Nexus action instead of intent
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

(defn handle-delete [e db block-id on-intent]
  "Handle Delete key - merge with next block if at end."
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        is-at-end (= (.-anchorOffset selection) (count text-content))]
    (when is-at-end
      (.preventDefault e)
      (on-intent {:type :merge-with-next :block-id block-id}))))

(defn handle-keydown [e db block-id on-intent]
  "Handle keyboard events while editing a block.

   Global shortcuts (Alt+Arrow, Shift+Arrow, Cmd/Alt+Shift+Arrow, Backspace for deletion)
   are handled by the global keydown handler when blocks are selected but NOT editing.

   This handler focuses on editing-specific behavior:
   - Arrow keys with cursor boundary detection for seamless navigation
   - Shift+Arrow for text selection OR block selection at boundaries
   - Enter to create new blocks
   - Shift+Enter to insert literal newline (Logseq parity)
   - Emacs navigation: Ctrl+A/E (line), Alt+A/E (block)
   - Escape to exit mode
   - Backspace for delete/merge at cursor boundaries (within text)
   - Tab/Shift+Tab for indent/outdent while editing"
  (let [key (.-key e)
        shift? (.-shiftKey e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        alt? (.-altKey e)
        ctrl? (.-ctrlKey e)
        target (.-target e)]
    (cond
      ;; === Emacs Line Navigation (macOS: Ctrl+A/E) ===
      ;; Ctrl+A - Move to beginning of current line
      (and (= key "a") ctrl? (not shift?) (not alt?))
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-pos (.-anchorOffset selection)
                ;; Find start of current line (search backward for \n)
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

      ;; Ctrl+E - Move to end of current line
      (and (= key "e") ctrl? (not shift?) (not alt?))
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-pos (.-anchorOffset selection)
                text-length (count text-content)
                ;; Find end of current line (search forward for \n)
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
      ;; Alt+A - Move to beginning of block
      (and (= key "a") alt? (not shift?) (not ctrl?))
      (do (.preventDefault e)
          (when-let [text-node (.-firstChild target)]
            (let [range (.createRange js/document)
                  sel (.getSelection js/window)]
              (.setStart range text-node 0)
              (.setEnd range text-node 0)
              (.removeAllRanges sel)
              (.addRange sel range))))

      ;; Alt+E - Move to end of block
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

      ;; Ctrl+P/N - Emacs-style navigation aliases (same as arrows)
      (and (= key "p") ctrl? (not shift?) (not alt?))
      (handle-arrow-up e db block-id on-intent)

      (and (= key "n") ctrl? (not shift?) (not alt?))
      (handle-arrow-down e db block-id on-intent)

      ;; Plain arrows (with boundary detection) - navigate between blocks while editing
      (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?))
      (handle-arrow-up e db block-id on-intent)

      (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?))
      (handle-arrow-down e db block-id on-intent)

      (and (= key "ArrowLeft") (not shift?) (not mod?) (not alt?))
      (handle-arrow-left e db block-id on-intent)

      (and (= key "ArrowRight") (not shift?) (not mod?) (not alt?))
      (handle-arrow-right e db block-id on-intent)

      ;; LOGSEQ PARITY: Shift+Enter inserts literal newline (doesn't create block)
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

      ;; Backspace - delete/merge at cursor boundary (within text editing)
      (and (= key "Backspace") (not shift?) (not mod?) (not alt?))
      (handle-backspace e db block-id on-intent)

      ;; Delete at end - merge with next block
      (and (= key "Delete") (not shift?) (not mod?) (not alt?))
      (handle-delete e db block-id on-intent)

      ;; Tab/Shift+Tab handled by global keymap (bindings_data.cljc :editing context)

      :else nil)))

;; ── Content Rendering with Block References, Embeds, and Page Refs ────────────

(defn- parse-all-refs
  "Parse text for all reference types: embeds, block refs, and page refs.

   Returns vector of segments with :type and type-specific data.
   Types: :text, :embed, :ref, :page-ref

   Embeds are parsed first (highest priority), then block refs, then page refs.
   This prevents ambiguity in overlapping patterns."
  [text]
  (when text
    ;; Find all matches with their positions
    (let [embed-matches (map (fn [[match id]]
                               {:type :embed
                                :match match
                                :id id
                                :start (.indexOf text match)})
                             (embeds/extract-embeds text))
          block-ref-matches (map (fn [[match id]]
                                   {:type :ref
                                    :match match
                                    :id id
                                    :start (.indexOf text match)})
                                 (block-refs/extract-refs text))
          page-ref-matches (map (fn [[match page]]
                                  {:type :page-ref
                                   :match match
                                   :page page
                                   :start (.indexOf text match)})
                                (page-refs/extract-refs text))

          ;; Combine all matches and sort by position
          all-matches (->> (concat embed-matches block-ref-matches page-ref-matches)
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

(declare Block) ; Forward declaration for BlockEmbed

(defn render-text-with-refs
  "Parse text for all reference types and render with appropriate components.

   Handles:
   - Block embeds: {{embed ((block-id))}} - full tree rendering
   - Block refs: ((block-id)) - inline text transclusion
   - Page refs: [[page-name]] - page links

   Returns a seq of strings and components mixed together (NOT wrapped in a container).
   The parent component is responsible for providing the container.
   Uses ref-set for cycle detection and embed-depth for limiting recursion."
  [db text ref-set embed-depth on-intent]
  (let [segments (parse-all-refs text)]
    (map (fn [segment]
           (case (:type segment)
             :text (:value segment)

             :ref (block-ref/BlockRef {:db db
                                       :block-id (:id segment)
                                       :ref-set ref-set})

             :embed [:div.inline-embed
                     {:style {:display "inline-block"
                              :vertical-align "top"
                              :width "100%"}}
                     (block-embed/BlockEmbed {:db db
                                              :block-id (:id segment)
                                              :embed-set ref-set
                                              :depth (or embed-depth 0)
                                              :max-depth 3
                                              :Block Block
                                              :on-intent on-intent})]

             :page-ref (page-ref/PageRef {:db db
                                          :page-name (:page segment)
                                          :on-intent on-intent})))
         segments)))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn Block
  "Reusable block component with Logseq-style editing behavior.

   Props:
   - db: application database
   - block-id: ID of block to render
   - depth: nesting depth (for indentation)
   - on-intent: callback for dispatching intents
   - embed-set: Set of block IDs in current embed chain (for cycle detection)
   - embed-depth: Current embed nesting depth (for limiting recursion)

   Uses plugin getters for all data access.
   Dispatches intents for all state changes."
  [{:keys [db block-id depth on-intent embed-set embed-depth]
    :or {embed-set #{} embed-depth 0}}]
  (let [children (get-in db [:children-by-parent block-id] [])
        selected? (q/selected? db block-id)
        focus? (= (q/focus db) block-id)
        editing? (= (q/editing-block-id db) block-id)
        text (get-in db [:nodes block-id :props :text] "")

        ;; Atom to track if we're programmatically exiting edit mode
        ;; This prevents blur event from firing exit-edit after Escape
        exiting-edit? (atom false)

        container-props
        {:key block-id
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
                         (on-intent {:type :selection :mode :extend :ids block-id})
                         (on-intent {:type :selection :mode :replace :ids block-id})))}}

        ;; Fold indicator bullet
        has-children? (seq (q/children db block-id))
        folded? (q/folded? db block-id)
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

        content
        (if editing?
          [:span.content-edit
           {:contentEditable true
            :suppressContentEditableWarning true
            :style {:outline "none"
                    :min-width "1px"
                    :display "inline-block"}
            :data-block-id block-id
            ;; CRITICAL: Add key to distinguish from .content-view span
            ;; This ensures Replicant treats them as different elements during mode transitions
            :key (str block-id "-edit")
            ;; Focus and cursor positioning on every render
            :replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
                                   ;; Don't run on unmount
                                   (when-not (= life-cycle :replicant.life-cycle/unmount)
                                     (let [cursor-pos (q/cursor-position db)]

                                       ;; CRITICAL: Use :replicant/remember to set textContent ONLY ONCE
                                       ;; After first initialization, browser manages contenteditable completely
                                       (when-not memory ; Only if we haven't initialized this node yet
                                         (set! (.-textContent node) text)
                                         (remember true)) ; Mark as initialized

                                       ;; Apply cursor position ONCE per cursor-pos value
                                       ;; CRITICAL: Set cursor position BEFORE calling .focus() to prevent cursor reset
                                       ;; Track the last applied cursor-pos on the DOM node to avoid reapplication
                                       (if cursor-pos
                                         (let [last-applied (aget node "__lastAppliedCursorPos")]
                                           (when (not= cursor-pos last-applied)
                                             (try
                                               (let [range (.createRange js/document)
                                                     sel (.getSelection js/window)
                                                     text-node (.-firstChild node)]
                                                 ;; Position cursor in the text node (only if text node exists)
                                                 (when (and text-node (= (.-nodeType text-node) 3))
                                                   (let [text-length (.-length text-node)
                                                         pos (cond
                                                               (= cursor-pos :start) 0
                                                               (= cursor-pos :end) text-length
                                                               (number? cursor-pos) (min cursor-pos text-length)
                                                               :else text-length)]
                                                     (.setStart range text-node pos)
                                                     (.setEnd range text-node pos)
                                                     (.removeAllRanges sel)
                                                     (.addRange sel range)
                                                     ;; Mark this cursor-pos as applied
                                                     (aset node "__lastAppliedCursorPos" cursor-pos)
                                                     ;; CRITICAL: Delay clearing cursor-position until AFTER this render cycle
                                                     ;; Otherwise the re-render with nil cursor-pos will reset cursor to position 0
                                                     (js/setTimeout #(on-intent {:type :clear-cursor-position}) 0))))
                                               (catch js/Error e
                                                 (js/console.error "Cursor positioning failed:" e))))
                                           ;; CRITICAL FIX: Always focus, even for empty blocks with no text node
                                           (.focus node))
                                         ;; No cursor-pos specified, just focus normally
                                         (.focus node))

                                       (update-mock-text! node (.-textContent node)))))
            :on {:input (fn [e]
                          (let [target (.-target e)
                                new-text (.-textContent target)]
                            (update-mock-text! target new-text)
                            (on-intent {:type :update-content
                                        :block-id block-id
                                        :text new-text})))
                 :paste (fn [e]
                          ;; LOGSEQ PARITY (FR-Clipboard-03): Handle paste with multi-paragraph splitting
                          (.preventDefault e)
                          (let [clipboard-data (.-clipboardData e)
                                pasted-text (.getData clipboard-data "text/plain")
                                selection (.getSelection js/window)
                                cursor-pos (.-anchorOffset selection)]
                            (on-intent {:type :paste-text
                                        :block-id block-id
                                        :cursor-pos cursor-pos
                                        :pasted-text pasted-text})))
                 :blur (fn [e]
                         ;; Only exit on blur if not already exiting via Escape
                         (when-not @exiting-edit?
                           ;; CRITICAL: Capture final text from contenteditable before exiting
                           ;; This prevents race conditions where the last input event hasn't fired
                           (let [final-text (.-textContent (.-target e))]
                             (on-intent {:type :update-content
                                         :block-id block-id
                                         :text final-text}))
                           (on-intent {:type :exit-edit})))
                 :keydown (fn [e]
                            (when (= (.-key e) "Escape")
                              (reset! exiting-edit? true))
                            (handle-keydown e db block-id on-intent))}}]
          [:span.content-view
           {:style {:min-width "1px"
                    :display "inline-block"
                    :cursor "text"}
            :data-block-id block-id
            ;; CRITICAL: Add key for consistent reconciliation with edit mode
            :key (str block-id "-view")
            ;; CRITICAL: Uncontrolled component - set textContent via lifecycle hook
            :replicant/on-render (fn [{:replicant/keys [node]}]
                                   (set! (.-textContent node) (or text "")))
            :on {:click (fn [e]
                          (.stopPropagation e)
                          ;; First click = select, second click (when focused) = enter edit mode
                          (if focus?
                            (on-intent {:type :enter-edit :block-id block-id})
                            (on-intent {:type :selection :mode :replace :ids block-id})))}}])

        children-el
        (when (seq children)
          (into [:div {:style {:margin-top "2px"}}]
                (map (fn [child-id]
                       (Block {:db db
                               :block-id child-id
                               :depth (inc depth)
                               :embed-set embed-set
                               :embed-depth embed-depth
                               :on-intent on-intent}))
                     children)))]

    (cond-> [:div.block container-props
             bullet
             content]
      ;; Only show children if not folded
      (and children-el (not folded?)) (conj children-el))))
