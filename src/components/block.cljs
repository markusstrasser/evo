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
  "Update hidden mock-text element with current contenteditable content.
   This enables cursor row position detection (Logseq technique)."
  [text]
  (when-let [mock-elem (js/document.getElementById "mock-text")]
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
          (.appendChild mock-elem span))))))

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
   Returns {:first-row? bool :last-row? bool}"
  [elem]
  (when-let [cursor-rect (get-caret-rect elem)]
    (let [tops (get-mock-text-tops)
          cursor-top (.-top cursor-rect)]
      {:first-row? (and (seq tops) (= (first tops) cursor-top))
       :last-row? (and (seq tops) (= (last tops) cursor-top))})))

(defn- has-text-selection?
  "Check if user has selected text within contenteditable."
  []
  (let [selection (.getSelection js/window)]
    (and selection
         (not (.-isCollapsed selection))
         (> (.-rangeCount selection) 0))))

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

      ;; At first row - navigate to previous block with cursor memory
      (:first-row? cursor-pos)
      (do (.preventDefault e)
          ;; NEW: Use cursor-memory navigation instead of simple :prev
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            (on-intent {:type :navigate-with-cursor-memory
                        :direction :up
                        :current-block-id block-id
                        :current-text text-content
                        :current-cursor-pos cursor-offset})))

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

      ;; At last row - navigate to next block with cursor memory
      (:last-row? cursor-pos)
      (do (.preventDefault e)
          ;; NEW: Use cursor-memory navigation instead of simple :next
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            (on-intent {:type :navigate-with-cursor-memory
                        :direction :down
                        :current-block-id block-id
                        :current-text text-content
                        :current-cursor-pos cursor-offset})))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-enter [e db block-id on-intent]
  (.preventDefault e)
  (let [selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)]
    ;; Use :smart-split instead of :split-at-cursor for context-aware behavior
    (on-intent {:type :smart-split
                :block-id block-id
                :cursor-pos cursor-pos})))

(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  ;; Just exit edit mode - element will unmount, view will mount
  (on-intent {:type :exit-edit}))

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

(defn handle-keydown [e db block-id on-intent]
  "Handle keyboard events while editing a block.
   
   Global shortcuts (Alt+Arrow, Shift+Arrow, Cmd/Alt+Shift+Arrow, Backspace for deletion)
   are handled by the global keydown handler when blocks are selected but NOT editing.
   
   This handler focuses on editing-specific behavior:
   - Arrow keys with cursor boundary detection for seamless navigation
   - Enter to create new blocks
   - Escape to exit edit mode
   - Backspace for delete/merge at cursor boundaries (within text)
   - Tab/Shift+Tab for indent/outdent while editing"
  (let [key (.-key e)
        shift? (.-shiftKey e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        alt? (.-altKey e)]
    (cond
      ;; Plain arrows (with boundary detection) - navigate between blocks while editing
      (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?))
      (handle-arrow-up e db block-id on-intent)

      (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?))
      (handle-arrow-down e db block-id on-intent)

      ;; Enter - create new block
      (and (= key "Enter") (not shift?) (not mod?) (not alt?))
      (handle-enter e db block-id on-intent)

      ;; Escape - exit edit mode
      (= key "Escape")
      (handle-escape e db block-id on-intent)

      ;; Backspace - delete/merge at cursor boundary (within text editing)
      (and (= key "Backspace") (not shift?) (not mod?) (not alt?))
      (handle-backspace e db block-id on-intent)

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

   Returns a vector of strings and components mixed together.
   Uses ref-set for cycle detection and embed-depth for limiting recursion."
  [db text ref-set embed-depth on-intent]
  (let [segments (parse-all-refs text)]
    (into [:span]
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
               segments))))

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
                                       (on-intent {:type :toggle-fold :block-id block-id})))}}
                (cond
                  (not has-children?) "•"
                  folded? "▸"
                  :else "▾")]

        ;; Track if we're in initial render to prevent spurious input events
        initializing? (atom true)

        content
        (if editing?
          [:span.content-edit
           {:contentEditable true
            :suppressContentEditableWarning true
            :style {:outline "none"
                    :min-width "1px"
                    :display "inline-block"}
            :data-block-id block-id
            ;; Use :replicant/on-render which fires on every render, not just mount
            :replicant/on-render (fn [{:replicant/keys [node]}]
                                   (let [current-text (.-textContent node)
                                         cursor-pos (q/cursor-position db)]

                                     ;; Always set the correct source text on initial render
                                     ;; This ensures we show the raw syntax (((id))) not rendered text
                                     (when @initializing?
                                       (set! (.-textContent node) text)
                                       (reset! initializing? false))

                                     ;; Focus the element
                                     (.focus node)

                                     ;; Set cursor position based on session state
                                     ;; NOTE: cursor-position is cleared by the caller (shell), not here
                                     (when (and cursor-pos (seq (.-textContent node)))
                                       (try
                                         (let [range (.createRange js/document)
                                               sel (.getSelection js/window)
                                               text-node (.-firstChild node)]
                                           ;; Position cursor in the text node
                                           (when (and text-node (= (.-nodeType text-node) 3))
                                             (let [text-length (.-length text-node)
                                                   pos (if (= cursor-pos :start) 0 text-length)]
                                               (.setStart range text-node pos)
                                               (.setEnd range text-node pos)
                                               (.removeAllRanges sel)
                                               (.addRange sel range))))
                                         (catch js/Error e
                                           (js/console.error "Cursor positioning failed:" e))))

                                     (update-mock-text! (.-textContent node))))
            :on {:input (fn [e]
                          ;; Ignore input events during initialization
                          (when-not @initializing?
                            (let [new-text (-> e .-target .-textContent)]
                              (update-mock-text! new-text)
                              (on-intent {:type :update-content
                                          :block-id block-id
                                          :text new-text}))))
                 :blur (fn [_e]
                         ;; Only exit on blur if not already exiting via Escape
                         (when-not @exiting-edit?
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
            :on {:click (fn [e]
                          (.stopPropagation e)
                          ;; First click = select, second click (when focused) = enter edit mode
                          (if focus?
                            (on-intent {:type :enter-edit :block-id block-id})
                            (on-intent {:type :selection :mode :replace :ids block-id})))}}
           ;; Render text with block references, embeds, and page refs
           (render-text-with-refs db text
                                  (conj embed-set block-id)
                                  embed-depth
                                  on-intent)])

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
