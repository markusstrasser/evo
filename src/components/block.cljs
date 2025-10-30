(ns components.block
  "Block component with Logseq-style editing behavior.

   Uses plugin getters for data and dispatches intents for all state changes.
   Implements cursor boundary detection for seamless up/down navigation."
  (:require [replicant.dom :as d]
            [kernel.query :as q]))

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

      ;; At first row - navigate to previous block
      (:first-row? cursor-pos)
      (do (.preventDefault e)
          (on-intent {:type :selection :mode :prev}))

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

      ;; At last row - navigate to next block
      (:last-row? cursor-pos)
      (do (.preventDefault e)
          (on-intent {:type :selection :mode :next}))

      ;; Otherwise - let browser handle cursor movement
      :else nil)))

(defn handle-enter [e db block-id on-intent]
  (.preventDefault e)
  (let [target (.-target e)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)]
    ;; TODO: Use split-at-cursor intent for proper implementation
    ;; For now, just create new block after
    (let [parent (get-in db [:derived :parent-of block-id])
          new-id (str "block-" (random-uuid))]
      (on-intent {:type :create-and-place
                  :id new-id
                  :parent parent
                  :after block-id}))))

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

;; ── Component ─────────────────────────────────────────────────────────────────

(defn Block
  "Reusable block component with Logseq-style editing behavior.

   Props:
   - db: application database
   - block-id: ID of block to render
   - depth: nesting depth (for indentation)
   - on-intent: callback for dispatching intents

   Uses plugin getters for all data access.
   Dispatches intents for all state changes."
  [{:keys [db block-id depth on-intent]}]
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

                                     ;; Only set text if node is truly empty (first render)
                                     (when (and (empty? current-text) (not= text ""))
                                       (set! (.-textContent node) text))

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
                          (let [new-text (-> e .-target .-textContent)]
                            (update-mock-text! new-text)
                            (on-intent {:type :update-content
                                        :block-id block-id
                                        :text new-text})))
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
           text])

        children-el
        (when (seq children)
          (into [:div {:style {:margin-top "2px"}}]
                (map (fn [child-id]
                       (Block {:db db
                               :block-id child-id
                               :depth (inc depth)
                               :on-intent on-intent}))
                     children)))]

    (cond-> [:div.block container-props
             bullet
             content]
      ;; Only show children if not folded
      (and children-el (not folded?)) (conj children-el))))
