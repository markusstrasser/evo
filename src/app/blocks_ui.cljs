(ns app.blocks-ui
  "Blocks UI demo - composition layer only.

   Demonstrates proper architecture:
   - Plugins provide getters and extend intent multimethods
   - Components use getters and dispatch intents
   - App just composes components and routes intents"
  (:require [replicant.dom :as d]
            [core.db :as DB]
            [core.intent :as intent]
            [core.transaction :as tx]
            [core.history :as H]
            [components.block :as block]
            [plugins.selection :as sel]
            [plugins.struct.core :as struct]
            [plugins.navigation :as nav]
            [plugins.editing :as edit]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defonce !db
  (atom (-> (DB/empty-db)
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
            (H/record)))) ;; Record initial state for undo

;; ── Intent dispatcher (routes to kernel or plugins) ──────────────────────────

(defn interpret!
  "Interpret ops through kernel, update DB, record history."
  [ops]
  (js/console.log "Interpreting ops:" (pr-str ops))
  (swap! !db (fn [db]
               (if (seq ops)
                 (let [db-recorded (H/record db)
                       result (tx/interpret db-recorded ops)]
                   (if (empty? (:issues result))
                     (:db result)
                     (do
                       (js/console.error "Interpret issues:" (pr-str (:issues result)))
                       db)))
                 db))))

(defn handle-intent
  "Single intent dispatcher - routes via multimethods.

   This is the ONLY place intents are dispatched.
   Components just call (on-intent {:type ...})."
  [intent-map]
  (js/console.log "Intent:" (pr-str intent-map))
  (let [db-before @!db
        {:keys [db ops path]} (intent/apply-intent db-before intent-map)]
    (case path
      :ops (interpret! ops) ;; Structural: interpret through kernel
      :db (swap! !db (constantly db)) ;; View: direct DB update
      :unknown (js/console.warn "Unknown intent type:" (:type intent-map)))))

;; ── Global keyboard shortcuts ─────────────────────────────────────────────────

(defn handle-global-keydown [e]
  "Global keyboard shortcuts - works on selected blocks and global commands."
  (let [key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        alt? (.-altKey e)
        db @!db
        focus-id (sel/get-focus db)
        has-selection? (sel/has-selection? db)
        editing? (edit/editing-block-id db)
        ;; Printable character check: single character, not a special key
        printable? (and (= 1 (.-length key))
                        (not mod?)
                        (not alt?)
                        (not (contains? #{"Enter" "Escape" "Tab" "Backspace" "Delete"} key)))]
    (cond
      ;; Enter - Create new block after focused block and enter edit mode (only when NOT already editing)
      (and (= key "Enter") (not shift?) (not mod?) (not alt?) focus-id (not editing?))
      (do (.preventDefault e)
          (let [parent (get-in db [:derived :parent-of focus-id])
                new-id (str "block-" (random-uuid))]
            (handle-intent {:type :create-and-place
                            :id new-id
                            :parent parent
                            :after focus-id})
            ;; Defer enter-edit to next tick to ensure create completes
            (js/setTimeout
             #(handle-intent {:type :enter-edit :block-id new-id})
             0)))

      ;; ArrowDown - Navigate to next sibling (plain, only when NOT editing)
      (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?) focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :select-next-sibling}))

      ;; ArrowUp - Navigate to previous sibling (plain, only when NOT editing)
      (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?) focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :select-prev-sibling}))

      ;; Tab - Indent selected blocks (only when NOT editing)
      (and (= key "Tab") (not shift?) (not mod?) (not alt?) has-selection? (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :indent-selected}))

      ;; Shift+Tab - Outdent selected blocks (only when NOT editing)
      (and (= key "Tab") shift? (not mod?) (not alt?) has-selection? (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :outdent-selected}))

      ;; Alt+ArrowDown - Navigate to next sibling with Alt modifier (only when NOT editing)
      (and (= key "ArrowDown") (not shift?) (not mod?) alt? focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :select-next-sibling}))

      ;; Alt+ArrowUp - Navigate to previous sibling with Alt modifier (only when NOT editing)
      (and (= key "ArrowUp") (not shift?) (not mod?) alt? focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :select-prev-sibling}))

      ;; Shift+ArrowDown - Extend selection to next sibling (only when NOT editing)
      (and (= key "ArrowDown") shift? (not mod?) (not alt?) focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :extend-to-next-sibling}))

      ;; Shift+ArrowUp - Extend selection to previous sibling (only when NOT editing)
      (and (= key "ArrowUp") shift? (not mod?) (not alt?) focus-id (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :extend-to-prev-sibling}))

      ;; Cmd/Alt+Shift+ArrowUp - Move selected blocks up (works in both edit and non-edit mode)
      (and (= key "ArrowUp") shift? (or mod? alt?) has-selection?)
      (do (.preventDefault e)
          (handle-intent {:type :move-selected-up}))

      ;; Cmd/Alt+Shift+ArrowDown - Move selected blocks down (works in both edit and non-edit mode)
      (and (= key "ArrowDown") shift? (or mod? alt?) has-selection?)
      (do (.preventDefault e)
          (handle-intent {:type :move-selected-down}))

      ;; Backspace - Delete selected blocks (only if not editing)
      (and (= key "Backspace") (not mod?) (not shift?) (not alt?)
           has-selection?
           (not editing?))
      (do (.preventDefault e)
          (handle-intent {:type :delete-selected}))

      ;; Printable character - Enter edit mode and let character through
      ;; This enables Logseq-style "start typing to edit" behavior
      (and printable? focus-id (not editing?))
      (do
        ;; Don't prevent default - let the character propagate to contenteditable
        (handle-intent {:type :enter-edit :block-id focus-id}))

      ;; Undo/Redo
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
   {:style {:width "100%"
            :height "100%"
            :position "absolute"
            :visibility "hidden"
            :top 0
            :left 0
            :pointer-events "none"
            :z-index -1000}}])

(defn Outline
  "Render outline tree by composing Block components."
  [{:keys [db root-id on-intent]}]
  (let [children (get-in db [:children-by-parent root-id] [])]
    (into [:div.outline]
          (map (fn [child-id]
                 (block/Block {:db db
                               :block-id child-id
                               :depth 0
                               :on-intent on-intent}))
               children))))

(defn DebugPanel [db]
  [:div.debug-panel
   {:style {:margin-top "30px"
            :padding "15px"
            :background-color "#f8f9fa"
            :border-radius "4px"
            :font-family "monospace"
            :font-size "12px"}}
   [:div [:strong "Selection: "] (pr-str (sel/get-selection db))]
   [:div [:strong "Focus: "] (pr-str (sel/get-focus db))]
   [:div [:strong "Editing: "] (pr-str (edit/editing-block-id db))]
   [:div {:style {:margin-top "10px"}} [:strong "Can undo: "] (str (H/can-undo? db))]
   [:div [:strong "Can redo: "] (str (H/can-redo? db))]])

(defn HotkeysReference []
  [:div.hotkeys-footer
   {:style {:margin-top "30px"
            :padding "20px"
            :background-color "#f0f0f0"
            :border-radius "4px"}}
   [:h4 "Keyboard Shortcuts (Logseq Style)"]
   [:div {:style {:display "grid"
                  :grid-template-columns "repeat(2, 1fr)"
                  :gap "10px"}}
    [:div
     [:h5 "Navigation"]
     [:div.hotkey-item "↑/↓ - Move cursor (or navigate blocks at boundary)"]
     [:div.hotkey-item "Esc - Exit edit mode"]
     [:div.hotkey-item "Click - Select block"]]
    [:div
     [:h5 "Editing"]
     [:div.hotkey-item "Enter - New block"]
     [:div.hotkey-item "Shift+Enter - Newline in block"]
     [:div.hotkey-item "Backspace - Delete/merge"]
     [:div.hotkey-item "Tab - Indent"]
     [:div.hotkey-item "Shift+Tab - Outdent"]]
    [:div
     [:h5 "Undo/Redo"]
     [:div.hotkey-item "⌘/Ctrl+Z - Undo"]
     [:div.hotkey-item "⌘/Ctrl+Shift+Z - Redo"]]]])

(defn App []
  "Main app - pure composition, no business logic."
  (let [db @!db]
    [:div.app
     {:style {:font-family "system-ui, -apple-system, sans-serif"
              :padding "20px"
              :max-width "800px"
              :margin "0 auto"}}

     ;; Mock-text for cursor detection
     (MockText)

     [:h2 "Blocks UI - Architectural Demo"]
     [:p {:style {:color "#666"}}
      "Demonstrating: Plugins (multimethods) → Components (getters/intents) → App (composition)"]

     ;; Main outline
     (Outline {:db db
               :root-id "page"
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
