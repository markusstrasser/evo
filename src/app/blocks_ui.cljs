(ns app.blocks-ui
  "Blocks UI demo - composition layer only.

   Demonstrates proper architecture:
   - Plugins provide getters and extend intent multimethods
   - Components use getters and dispatch intents
   - App just composes components and routes intents"
  (:require [replicant.dom :as d]
            [core.db :as DB]
            [core.intent :as intent]
            [core.interpret :as I]
            [core.history :as H]
            [components.block :as block]
            [plugins.selection.core :as sel]
            [plugins.struct.core :as struct]
            [plugins.navigation.core :as nav]
            [plugins.editing.core :as edit]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defonce !db
  (atom (-> (DB/empty-db)
            ;; Create sample outline structure
            (I/interpret [{:op :create-node :id "page" :type :page :props {:title "My Page"}}
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
            (H/record))))  ;; Record initial state for undo

;; ── Intent dispatcher (routes to kernel or plugins) ──────────────────────────

(defn interpret!
  "Interpret ops through kernel, update DB, record history."
  [ops]
  (js/console.log "Interpreting ops:" (pr-str ops))
  (swap! !db (fn [db]
               (let [result (I/interpret db ops)]
                 (if (empty? (:issues result))
                   (H/record (:db result))
                   (do
                     (js/console.error "Interpret issues:" (pr-str (:issues result)))
                     db))))))

(defn handle-intent
  "Single intent dispatcher - routes via multimethods.

   This is the ONLY place intents are dispatched.
   Components just call (on-intent {:type ...})."
  [intent-map]
  (js/console.log "Intent:" (pr-str intent-map))
  (let [db-before @!db
        {:keys [db ops path]} (intent/apply-intent db-before intent-map)]
    (case path
      :ops (interpret! ops)       ;; Structural: interpret through kernel
      :db  (swap! !db (constantly db))  ;; View: direct DB update
      :unknown (js/console.warn "Unknown intent type:" (:type intent-map)))))

;; ── Global keyboard shortcuts ─────────────────────────────────────────────────

(defn handle-global-keydown [e]
  "Global keyboard shortcuts (Cmd+Z, etc) - NOT block-level events."
  (let [key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)]
    (cond
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
  [:div {:style {:margin-top "30px"
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
     [:div "↑/↓ - Move cursor (or navigate blocks at boundary)"]
     [:div "Esc - Exit edit mode"]
     [:div "Click - Select block"]]
    [:div
     [:h5 "Editing"]
     [:div "Enter - New block"]
     [:div "Shift+Enter - Newline in block"]
     [:div "Backspace - Delete/merge"]
     [:div "Tab - Indent"]
     [:div "Shift+Tab - Outdent"]]
    [:div
     [:h5 "Undo/Redo"]
     [:div "⌘/Ctrl+Z - Undo"]
     [:div "⌘/Ctrl+Shift+Z - Redo"]]]])

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

  ;; Set up global keyboard listener (Cmd+Z, etc)
  (.addEventListener js/document "keydown" handle-global-keydown)

  ;; Set up auto-render on state changes
  (add-watch !db :render (fn [_ _ _ _] (render!)))

  (render!))
