(ns components.block
  "Block component with Logseq-style editing behavior.

   Pure view + intent dispatch. Cursor boundary detection for arrow navigation."
  (:require [replicant.dom :as d]
            [kernel.query :as q]
            [components.mock-text :as mock-text]
            [keymap.core :as keymap]
            [plugins.editing :as edit]
            [plugins.selection :as sel]))

;; ── Keyboard handlers ─────────────────────────────────────────────────────────

(defn- handle-editing-keydown
  "Minimal editing keydown handler - delegates to keymap resolver.

   Only intercepts arrow keys for cursor boundary navigation.
   Everything else dispatches intent from keymap resolver."
  [e db block-id text on-intent]
  (let [key (.-key e)
        target (.-target e)
        event (keymap/parse-dom-event e)
        intent (keymap/resolve-event event db)]

    (cond
      ;; Arrow up/down - check cursor boundary first
      (and (= key "ArrowUp") (not (:shift event)) (not (:mod event)) (not (:alt event)))
      (let [boundary (mock-text/cursor-boundary target text)]
        (cond
          (:has-selection? boundary)
          (let [selection (.getSelection js/window)]
            (.preventDefault e)
            (.collapseToStart selection))

          (:first-row? boundary)
          (do (.preventDefault e)
              (on-intent {:type :navigate-up :block-id block-id}))

          :else nil))

      (and (= key "ArrowDown") (not (:shift event)) (not (:mod event)) (not (:alt event)))
      (let [boundary (mock-text/cursor-boundary target text)]
        (cond
          (:has-selection? boundary)
          (let [selection (.getSelection js/window)]
            (.preventDefault e)
            (.collapseToEnd selection))

          (:last-row? boundary)
          (do (.preventDefault e)
              (on-intent {:type :navigate-down :block-id block-id}))

          :else nil))

      ;; Enter - create new block (special case: needs parent lookup)
      (and (= key "Enter") (not (:shift event)) (not (:mod event)) (not (:alt event)))
      (let [parent (get-in db [:derived :parent-of block-id])
            new-id (str "block-" (random-uuid))]
        (.preventDefault e)
        (on-intent {:type :create-and-place
                    :id new-id
                    :parent parent
                    :after block-id}))

      ;; Backspace - delete/merge at cursor boundary
      (and (= key "Backspace") (not (:shift event)) (not (:mod event)) (not (:alt event)))
      (let [text-content (.-textContent target)
            selection (.getSelection js/window)
            is-at-start (and selection (= (.-anchorOffset selection) 0))]
        (when (or (empty? text-content) is-at-start)
          (.preventDefault e)
          (if (empty? text-content)
            (on-intent {:type :delete :id block-id})
            (on-intent {:type :merge-with-prev :block-id block-id}))))

      ;; All other keys - use keymap resolver
      intent
      (do (.preventDefault e)
          (on-intent intent))

      ;; No binding - let browser handle
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
                         (on-intent {:type :extend-selection :ids block-id})
                         (on-intent {:type :select :ids block-id})))}}

        bullet [:span {:style {:margin-right "8px"}} "•"]

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
                                   ;; Set text content if empty
                                   (when (empty? (.-textContent node))
                                     (set! (.-textContent node) text))

                                   ;; Focus the element
                                   (.focus node)

                                   ;; Set cursor to end (only if there's content)
                                   (when-not (empty? (.-textContent node))
                                     (try
                                       (let [range (.createRange js/document)
                                             sel (.getSelection js/window)]
                                         (.selectAllChildren range node)
                                         (.collapseToEnd range)
                                         (.removeAllRanges sel)
                                         (.addRange sel range))
                                       (catch js/Error e
                                         (js/console.error "Cursor error:" e))))
            :on {:input (fn [e]
                          (let [new-text (-> e .-target .-textContent)]
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
                            (handle-editing-keydown e db block-id text on-intent))}}]
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
                            (on-intent {:type :select :ids block-id})))}}
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
      children-el (conj children-el))))
