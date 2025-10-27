(ns shell.blocks-ui
  "Blocks UI demo - composition layer only.

   Demonstrates proper architecture:
   - Plugins provide getters and extend intent multimethods
   - Components use getters and dispatch intents
   - App just composes components and routes intents"
  (:require [replicant.dom :as d]
            [kernel.db :as DB]
            [kernel.api :as api]
            [kernel.query :as q]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [components.block :as block]
            [plugins.selection :as sel]
            [plugins.struct :as struct]
            [plugins.editing :as edit]
            [plugins.folding]  ;; Load to register fold/zoom intents
            [plugins.smart-editing]  ;; Load to register smart editing intents
            [keymap.core :as keymap]
            [keymap.bindings :as bindings]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defonce !db
         (atom
           (-> (DB/empty-db)
               ;; Create sample outline structure
               (tx/interpret [{:op :create-node :id "page" :type :page :props {:title "My Page"}}
                              {:op :place :id "page" :under :doc :at :last}
                              {:op :create-node :id "a" :type :block :props {:text "First block"}}
                              {:op :place :id "a" :under "page" :at :last}
                              {:op :create-node :id "b" :type :block :props {:text "Second block"}}
                              {:op :place :id "b" :under "page" :at :last}
                              {:op :create-node :id "c" :type :block :props {:text "Third block\nwith multiple\nlines for testing"}}
                              {:op :place :id "c" :under "page" :at :last}
                              {:op :create-node :id "d" :type :block :props {:text "Nested block"}}
                              {:op :place :id "d" :under "b" :at :last}])
               :db
               (H/record))))                                ;; Record initial state for undo

;; ── Intent dispatcher ─────────────────────────────────────────────────────────

(defn handle-intent
  "Single intent dispatcher - uses unified API façade.

   This is the ONLY place intents are dispatched.
   Components just call (on-intent {:type ...})."
  [intent-map]
  (js/console.log "Intent:" (pr-str intent-map))
  (swap! !db (fn [db]
               (let [{:keys [db issues]} (api/dispatch db intent-map)]
                 (when (seq issues)
                   (js/console.error "Intent validation failed:" (pr-str issues)))
                 db))))

;; ── Global keyboard shortcuts (Keymap Resolver) ───────────────────────────────

(defn handle-global-keydown [e]
  "Global keyboard shortcuts via central keymap resolver.

   Single source of truth: keymap/bindings.cljc registers all bindings.
   This function just resolves key event → intent type → dispatch."
  (let [event (keymap/parse-dom-event e)
        db @!db
        key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        focus-id (q/focus db)
        editing? (q/editing-block-id db)
        intent-type (keymap/resolve-intent-type event db)

        ;; Cursor boundary detection for arrow key navigation in edit mode
        editable-el (when editing? (.-activeElement js/document))
        at-start? (when (and editable-el (= "true" (.-contentEditable editable-el)))
                    (try
                      (let [sel (.getSelection js/window)]
                        (when (pos? (.-rangeCount sel))
                          (let [range (.getRangeAt sel 0)
                                node (.-startContainer range)
                                offset (.-startOffset range)]
                            ;; At start if offset is 0 and we're in the first text node
                            (and (zero? offset)
                                 (or (= node editable-el)
                                     (= node (.-firstChild editable-el)))))))
                      (catch js/Error e
                        (js/console.log "Boundary detection error:" e)
                        false)))
        at-end? (when (and editable-el (= "true" (.-contentEditable editable-el)))
                  (try
                    (let [sel (.getSelection js/window)]
                      (when (pos? (.-rangeCount sel))
                        (let [range (.getRangeAt sel 0)
                              node (.-endContainer range)
                              offset (.-endOffset range)
                              text-content (or (.-textContent editable-el) "")
                              text-length (count text-content)]
                          ;; At end if cursor is at the end of the text
                          (= offset text-length))))
                    (catch js/Error e
                      (js/console.log "Boundary detection error:" e)
                      false)))

        ;; Printable character check for "start typing to edit" behavior
        printable? (and (= 1 (.-length key))
                        (not (:mod event))
                        (not (:alt event))
                        (not (contains? #{"Enter" "Escape" "Tab" "Backspace" "Delete"} key)))]

    (cond
      ;; Arrow navigation at boundaries while editing (Logseq-style)
      ;; Navigate up: exit edit, go to prev block, enter edit at END
      (and editing? (= key "ArrowUp") at-start? (not mod?) (not shift?))
      (do (.preventDefault e)
          (let [prev-id (get-in db [:derived :prev-id-of editing?])]
            (when prev-id
              (handle-intent {:type :exit-edit})
              (js/setTimeout
                #(do (handle-intent {:type :selection :mode :replace :ids prev-id})
                     (handle-intent {:type :enter-edit :block-id prev-id :cursor-at :end})
                     ;; Clear cursor-position after component has applied it
                     (js/setTimeout
                       #(handle-intent {:type :update-node
                                        :id "session/ui"
                                        :props {:cursor-position nil}})
                       50))
                10))))

      ;; Navigate down: exit edit, go to next block, enter edit at START
      (and editing? (= key "ArrowDown") at-end? (not mod?) (not shift?))
      (do (.preventDefault e)
          (let [next-id (get-in db [:derived :next-id-of editing?])]
            (when next-id
              (handle-intent {:type :exit-edit})
              (js/setTimeout
                #(do (handle-intent {:type :selection :mode :replace :ids next-id})
                     (handle-intent {:type :enter-edit :block-id next-id :cursor-at :start})
                     ;; Clear cursor-position after component has applied it
                     (js/setTimeout
                       #(handle-intent {:type :update-node
                                        :id "session/ui"
                                        :props {:cursor-position nil}})
                       50))
                10))))

      ;; Keymap-resolved intent
      intent-type
      (do (.preventDefault e)
          (let [;; Inject focused block-id for fold/zoom intents
                intent-with-id (if (and (map? intent-type)
                                       (#{:toggle-fold :collapse :expand-all :zoom-in} (:type intent-type))
                                       focus-id)
                                (assoc intent-type :block-id focus-id)
                                intent-type)]
            (cond
              ;; Map intent: use directly (with injected block-id if needed)
              (map? intent-with-id)
              (handle-intent intent-with-id)

              ;; Keyword intent: wrap in :type
              :else
              (handle-intent {:type intent-with-id}))))

      ;; Printable character - Enter edit mode (Logseq-style "start typing")
      (and printable? focus-id (not editing?))
      (handle-intent {:type :enter-edit :block-id focus-id})

      ;; Undo/Redo (not in keymap yet - direct handling)
      (and mod? shift? (= key "z"))
      (do (.preventDefault e)
          (swap! !db (fn [db] (or (H/redo db) db))))

      (and mod? (= key "z"))
      (do (.preventDefault e)
          (swap! !db (fn [db] (or (H/undo db) db)))))))

;; ── Rendering ─────────────────────────────────────────────────────────────────

(defn MockText
  "Hidden element for cursor position detection (Logseq technique)."
  []
  [:div#mock-text
   {:style {:width          "100%"
            :height         "100%"
            :position       "absolute"
            :visibility     "hidden"
            :top            0
            :left           0
            :pointer-events "none"
            :z-index        -1000}}])

(defn Outline
  "Render outline tree by composing Block components."
  [{:keys [db root-id on-intent]}]
  (let [children (get-in db [:children-by-parent root-id] [])]
    (into [:div.outline]
          (map (fn [child-id]
                 (block/Block {:db        db
                               :block-id  child-id
                               :depth     0
                               :on-intent on-intent}))
               children))))

(defn DebugPanel [db]
  [:div.debug-panel
   {:style {:margin-top       "30px"
            :padding          "15px"
            :background-color "#f8f9fa"
            :border-radius    "4px"
            :font-family      "monospace"
            :font-size        "12px"}}
   [:div [:strong "Selection: "] (pr-str (q/selection db))]
   [:div [:strong "Focus: "] (pr-str (q/focus db))]
   [:div [:strong "Editing: "] (pr-str (q/editing-block-id db))]
   [:div {:style {:margin-top "10px"}} [:strong "Can undo: "] (str (H/can-undo? db))]
   [:div [:strong "Can redo: "] (str (H/can-redo? db))]])

(defn HotkeysReference []
  [:div.hotkeys-footer
   {:style {:margin-top       "30px"
            :padding          "20px"
            :background-color "#f0f0f0"
            :border-radius    "4px"}}
   [:h4 "Keyboard Shortcuts (Logseq Style)"]
   [:div {:style {:display               "grid"
                  :grid-template-columns "repeat(3, 1fr)"
                  :gap                   "10px"}}
    [:div
     [:h5 "Navigation"]
     [:div.hotkey-item "↑/↓ - Move cursor (or navigate blocks at boundary)"]
     [:div.hotkey-item "Shift+↑/↓ - Extend selection"]
     [:div.hotkey-item "Esc - Exit edit mode"]
     [:div.hotkey-item "Click - Select block"]
     [:div.hotkey-item "Shift+Click - Extend selection"]]
    [:div
     [:h5 "Editing"]
     [:div.hotkey-item "Enter - New block"]
     [:div.hotkey-item "Shift+Enter - Newline in block"]
     [:div.hotkey-item "Backspace - Delete/merge"]
     [:div.hotkey-item "Tab - Indent"]
     [:div.hotkey-item "Shift+Tab - Outdent"]
     [:div.hotkey-item "⌘+Enter - Toggle checkbox"]]
    [:div
     [:h5 "Folding & Zoom"]
     [:div.hotkey-item "Click bullet - Toggle fold"]
     [:div.hotkey-item "⌘+; - Toggle fold"]
     [:div.hotkey-item "⌘+↑ - Collapse"]
     [:div.hotkey-item "⌘+↓ - Expand all"]
     [:div.hotkey-item "⌘+. - Zoom in"]
     [:div.hotkey-item "⌘+, - Zoom out"]]
    [:div
     [:h5 "Undo/Redo"]
     [:div.hotkey-item "⌘/Ctrl+Z - Undo"]
     [:div.hotkey-item "⌘/Ctrl+Shift+Z - Redo"]]]])

(defn App []
  "Main app - pure composition, no business logic."
  (let [db @!db]
    [:div.app
     {:style {:font-family "system-ui, -apple-system, sans-serif"
              :padding     "20px"
              :max-width   "800px"
              :margin      "0 auto"}}

     ;; Mock-text for cursor detection
     (MockText)

     [:h2 "Blocks UI - Architectural Demo"]
     [:p {:style {:color "#666"}}
      "Demonstrating: Plugins (multimethods) → Components (getters/intents) → App (composition)"]

     ;; Main outline
     (Outline {:db        db
               :root-id   "page"
               :on-intent handle-intent})

     ;; Debug info
     (DebugPanel db)

     ;; Hotkeys reference
     (HotkeysReference)]))

;; ── Main ──────────────────────────────────────────────────────────────────────

(defn render! []
  (d/render (js/document.getElementById "root")
            (App)))

(defn main []
  (js/console.log "Blocks UI starting with proper architecture...")

  ;; Initialize keyboard bindings (explicit, not side-effect)
  (bindings/reload!)

  ;; Enable lifecycle hooks (required for :replicant/on-mount to work)
  (d/set-dispatch!
    (fn [event-data handler-data]
      (cond
        ;; Handle lifecycle hooks
        (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
        (when (fn? handler-data)
          (handler-data event-data))

        ;; Handle DOM events (if we use data-driven events in the future)
        (= :replicant.trigger/dom-event (:replicant/trigger event-data))
        (when (fn? handler-data)
          (handler-data (:replicant/dom-event event-data))))))

  ;; Set up global keyboard listener (Cmd+Z, etc)
  (.addEventListener js/document "keydown" handle-global-keydown)

  ;; Set up auto-render on state changes
  (add-watch !db :render (fn [_ _ _ _] (render!)))

  (render!))
