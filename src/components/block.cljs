(ns components.block
  "Block component with Logseq-style editing behavior.

   Uses plugin getters for data and dispatches intents for all state changes.
   Implements cursor boundary detection for seamless up/down navigation.

   ARCHITECTURE: Single source of truth - DB owns text + cursor state.
   DOM driver (evo.dom.editable) keeps DOM in sync via MutationObserver + rollback."
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [kernel.constants :as const]
            [parser.block-refs :as block-refs]
            [parser.embeds :as embeds]
            [parser.page-refs :as page-refs]
            [plugins.slash-commands :as slash]
            [components.block-ref :as block-ref]
            [components.block-embed :as block-embed]
            [components.page-ref :as page-ref]
            [util.text-selection :as text-sel]
            [evo.dom.editable :as editable]))

;; ── Cursor row detection ──────────────────────────────────────────────────────

(defn- detect-cursor-row-position
  "Detect if cursor is on first/last row of contenteditable using Range API.
   Returns {:first-row? bool :last-row? bool}

   With controlled editable architecture, we use DOM Range API directly
   instead of the old mock-text technique."
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

   Routes to slash menu when active, otherwise handles normal editing.
   
   Slash menu gets priority for:
   - Arrow keys (navigation)
   - Enter (selection)
   - Escape (close)
   
   Otherwise delegates to normal editing handlers."
  (let [slash-menu-active? (get-in db [:nodes const/session-ui-id :props :slash-menu])
        key (.-key e)
        shift? (.-shiftKey e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        alt? (.-altKey e)
        ctrl? (.-ctrlKey e)
        target (.-target e)]

    (if slash-menu-active?
      ;; === SLASH MENU ACTIVE - Route keys to menu ===
      (cond
        ;; Arrow down / Ctrl+N - next command
        (or (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?))
            (and (= key "n") ctrl? (not shift?) (not alt?)))
        (do (.preventDefault e)
            (on-intent {:type :slash-menu/next}))

        ;; Arrow up / Ctrl+P - previous command
        (or (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?))
            (and (= key "p") ctrl? (not shift?) (not alt?)))
        (do (.preventDefault e)
            (on-intent {:type :slash-menu/prev}))

        ;; Enter - select command
        (and (= key "Enter") (not shift?) (not mod?) (not alt?))
        (do (.preventDefault e)
            (on-intent {:type :slash-menu/select}))

        ;; Escape - close menu
        (= key "Escape")
        (do (.preventDefault e)
            (on-intent {:type :slash-menu/close}))

        ;; Backspace - update search (let it propagate, input handler will update)
        (= key "Backspace")
        nil ; Let browser handle, input event will trigger search update

        ;; Other keys - let them propagate (typing updates search)
        :else nil)

      ;; === NORMAL EDITING (No slash menu) ===
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

        :else nil))))

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

        edit-key (str block-id "-edit")
        view-key (str block-id "-view")
        _ (.log js/console "[block] Keys:" (clj->js {:editing? editing? :edit-key edit-key :view-key view-key}))

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
            :replicant/key edit-key
            ;; ARCHITECTURE: Controlled contenteditable with single source of truth (DB)
            ;; On mount: Setup MutationObserver + rollback pattern + focus element
            :replicant/on-mount
            (fn [{:replicant/keys [node]}]
              (let [initial-cursor (q/cursor-position db)
                    ;; Normalize cursor to map format {:anchor N :head N}
                    cursor-map (cond
                                 (map? initial-cursor) initial-cursor
                                 (number? initial-cursor) {:anchor initial-cursor :head initial-cursor}
                                 (= initial-cursor :start) {:anchor 0 :head 0}
                                 (= initial-cursor :end) (let [len (count text)]
                                                           {:anchor len :head len})
                                 :else {:anchor (count text) :head (count text)})

                    ;; Setup controlled editable with MutationObserver
                    cleanup! (editable/setup-controlled-editable!
                               node
                               (fn on-change [new-text new-cursor]
                                 ;; User edited → dispatch with text + cursor atomically
                                 (on-intent {:type :update-content
                                             :block-id block-id
                                             :text new-text
                                             :cursor-pos new-cursor})

                                 ;; Handle slash menu
                                 (let [slash-active? (get-in db [:nodes const/session-ui-id :props :slash-menu])
                                       pos (:anchor new-cursor)]
                                   (if slash-active?
                                     (on-intent {:type :slash-menu/update-search
                                                 :block-id block-id
                                                 :cursor-pos pos})
                                     (when-let [trigger (slash/detect-slash-trigger new-text pos)]
                                       (on-intent {:type :slash-menu/open
                                                   :block-id block-id
                                                   :trigger-pos (:trigger-pos trigger)})))))
                               text
                               cursor-map)]
                ;; Store cleanup for unmount
                (aset node "__editable-cleanup" cleanup!)
                ;; Focus element so keyboard events work
                (.focus node)
                (.log js/console "[on-mount] MutationObserver setup complete + focused" (clj->js {:block-id block-id}))))

            ;; On unmount: Disconnect observer
            :replicant/on-unmount
            (fn [{:replicant/keys [node]}]
              (when-let [cleanup! (aget node "__editable-cleanup")]
                (cleanup!)))

            ;; On render: Update DOM to reflect DB state
            ;; NOTE: With :replicant/key fix, :on-mount fires reliably so we don't need duplicate setup here
            :replicant/on-render
            (fn [{:replicant/keys [node life-cycle]}]
              (when (and (not= life-cycle :replicant.life-cycle/unmount)
                         (aget node "__editable-cleanup"))  ; Only if observer is set up
                (let [cursor-pos (q/cursor-position db)
                      cursor-map (cond
                                   (map? cursor-pos) cursor-pos
                                   (number? cursor-pos) {:anchor cursor-pos :head cursor-pos}
                                   (= cursor-pos :start) {:anchor 0 :head 0}
                                   (= cursor-pos :end) (let [len (count text)]
                                                         {:anchor len :head len})
                                   :else {:anchor (count text) :head (count text)})]
                  ;; Update DOM only - don't trigger new renders
                  (editable/update-controlled-editable! node text cursor-map))))
            ;; ARCHITECTURE: Controlled editable handles input/paste via MutationObserver
            ;; Only need keydown for keyboard shortcuts + blur for exit
            :on {:blur (fn [e]
                         ;; CRITICAL: Only exit edit mode if we're truly blurring (not just switching blocks)
                         ;; Use setTimeout to let React/Replicant finish rendering the new focused element
                         (js/setTimeout
                           (fn []
                             (when-not @exiting-edit?
                               ;; Check if another contenteditable is now focused (block creation/navigation)
                               (let [active-elem (.-activeElement js/document)
                                     still-editing? (and active-elem
                                                        (= (.-contentEditable active-elem) "true"))]
                                 (when-not still-editing?
                                   (on-intent {:type :exit-edit})))))
                           0))
                 :keydown (fn [e]
                            (.log js/console "keydown event!" (clj->js {:key (.-key e)}))
                            (when (= (.-key e) "Escape")
                              (reset! exiting-edit? true))
                            (handle-keydown e db block-id on-intent))

                 ;; Keep paste handler for multi-paragraph splitting (Logseq parity)
                 :paste (fn [e]
                          (.preventDefault e)
                          (let [clipboard-data (.-clipboardData e)
                                pasted-text (.getData clipboard-data "text/plain")
                                ;; Get cursor from DOM
                                cursor-info (editable/get-position (.-target e))
                                cursor-pos (:anchor cursor-info)]
                            (on-intent {:type :paste-text
                                        :block-id block-id
                                        :cursor-pos cursor-pos
                                        :pasted-text pasted-text})))}}]
          [:span.content-view
           {:style {:min-width "1px"
                    :display "inline-block"
                    :cursor "text"}
            :data-block-id block-id
            ;; CRITICAL: Add key for consistent reconciliation with edit mode
            :replicant/key view-key
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
