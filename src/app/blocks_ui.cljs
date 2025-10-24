(ns app.blocks-ui
  "Simple blocks UI for testing structural editing with Logseq hotkeys."
  (:require [replicant.dom :as d]
            [core.db :as DB]
            [core.intent :as intent]
            [core.interpret :as I]
            [core.history :as H]
            [plugins.selection.core :as sel]
            [plugins.struct.core :as struct]))

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
                          {:op :create-node :id "c" :type :block :props {:text "Third block"}}
                          {:op :place :id "c" :under "page" :at :last}
                          {:op :create-node :id "d" :type :block :props {:text "Nested block"}}
                          {:op :place :id "d" :under "b" :at :last}])
            :db
            (H/record))))  ;; Record initial state for undo

;; ── Helper functions ──────────────────────────────────────────────────────────

(defn update-db! [f & args]
  (swap! !db #(apply f % args)))

(defn interpret! [ops]
  (js/console.log "interpret! called with ops:" (pr-str ops))
  (update-db! (fn [db]
                (js/console.log "DB before interpret:" (pr-str (get-in db [:children-by-parent "page"])))
                (let [result (I/interpret db ops)]
                  (js/console.log "interpret result issues:" (pr-str (:issues result)))
                  (js/console.log "DB after interpret:" (pr-str (get-in (:db result) [:children-by-parent "page"])))
                  (if (empty? (:issues result))
                    (:db result)
                    (do
                      (js/console.error "Interpret issues:" (pr-str (:issues result)))
                      db))))))

(defn with-history! [f]
  (update-db! H/record)  ;; Save before action
  (f)
  (update-db! identity))  ;; Trigger re-render

(defn apply-intent!
  "Apply an intent through the unified router.
   Structural intents are interpreted, view intents update DB directly."
  [intent]
  (let [db-before @!db
        {:keys [db ops path]} (intent/apply-intent db-before intent)]
    (case path
      :ops (interpret! ops)  ;; Structural: interpret ops
      :db  (reset! !db db)   ;; View: update db directly
      :unknown (js/console.warn "Unknown intent type:" (:type intent)))))

;; ── Keyboard handlers ─────────────────────────────────────────────────────────

(defn handle-keydown [e]
  (let [key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        alt? (.-altKey e)]

    (cond
      ;; Undo/Redo
      (and mod? shift? (= key "z"))
      (do (.preventDefault e)
          (update-db! (fn [db] (or (H/redo db) db))))

      (and mod? (= key "z"))
      (do (.preventDefault e)
          (update-db! (fn [db] (or (H/undo db) db))))

      ;; Shift+Enter - insert newline within block (don't create new block)
      (and (= key "Enter") shift? (not mod?) (not alt?))
      ;; Let browser handle newline insertion naturally
      nil

      ;; Enter - create new block after current
      (and (= key "Enter") (not shift?) (not mod?) (not alt?))
      (do (.preventDefault e)
          (with-history!
            #(let [db @!db
                   focus (sel/get-focus db)
                   parent (get-in db [:derived :parent-of focus])
                   new-id (str "block-" (random-uuid))]
               (when (and focus parent)
                 (interpret! [{:op :create-node :id new-id :type :block :props {:text ""}}
                              {:op :place :id new-id :under parent :at {:after focus}}])
                 ;; Select and focus the new block
                 (update-db! sel/select new-id)
                 ;; Focus the contentEditable span after render
                 (js/setTimeout
                   (fn []
                     (when-let [elem (js/document.querySelector (str "[data-block-id='" new-id "']"))]
                       (.focus elem)))
                   50)))))

      ;; Move block up/down
      (and mod? shift? (= key "ArrowUp"))
      (do (.preventDefault e)
          (with-history!
            #(let [db @!db
                   focus (sel/get-focus db)
                   prev (get-in db [:derived :prev-id-of focus])
                   parent (get-in db [:derived :parent-of focus])]
               (when (and focus prev parent)
                 (interpret! [{:op :place :id focus :under parent :at {:before prev}}])))))

      (and mod? shift? (= key "ArrowDown"))
      (do (.preventDefault e)
          (with-history!
            #(let [db @!db
                   focus (sel/get-focus db)
                   next (get-in db [:derived :next-id-of focus])
                   parent (get-in db [:derived :parent-of focus])]
               (when (and focus next parent)
                 (interpret! [{:op :place :id focus :under parent :at {:after next}}])))))

      ;; Indent/Outdent (exit edit mode after)
      (and (not shift?) (= key "Tab"))
      (do (.preventDefault e)
          (with-history!
            #(apply-intent! {:type :indent-selected}))
          ;; Blur to exit edit mode (Logseq behavior)
          (when-let [focused-elem (.-activeElement js/document)]
            (when (.-contentEditable focused-elem)
              (.blur focused-elem))))

      (and shift? (= key "Tab"))
      (do (.preventDefault e)
          (with-history!
            #(apply-intent! {:type :outdent-selected}))
          ;; Blur to exit edit mode (Logseq behavior)
          (when-let [focused-elem (.-activeElement js/document)]
            (when (.-contentEditable focused-elem)
              (.blur focused-elem))))

      ;; Navigation: Up/Down arrows (select different block)
      (and (= key "ArrowDown") (not shift?) (not mod?) (not alt?))
      (do (.preventDefault e)
          (apply-intent! {:type :select-next-sibling}))

      (and (= key "ArrowUp") (not shift?) (not mod?) (not alt?))
      (do (.preventDefault e)
          (apply-intent! {:type :select-prev-sibling}))

      ;; Alt+Shift+Up/Down - same as Shift+Up/Down (extend selection)
      (and alt? shift? (= key "ArrowDown"))
      (do (.preventDefault e)
          (apply-intent! {:type :extend-to-next-sibling}))

      (and alt? shift? (= key "ArrowUp"))
      (do (.preventDefault e)
          (apply-intent! {:type :extend-to-prev-sibling}))

      ;; Extend selection (Shift+Up/Down)
      (and shift? (not mod?) (not alt?) (= key "ArrowDown"))
      (do (.preventDefault e)
          (apply-intent! {:type :extend-to-next-sibling}))

      (and shift? (not mod?) (not alt?) (= key "ArrowUp"))
      (do (.preventDefault e)
          (apply-intent! {:type :extend-to-prev-sibling}))

      ;; Backspace - merge with previous block if at start of empty/start of block
      (and (= key "Backspace") (not (sel/has-selection? @!db)))
      (let [target (.-target e)
            text-content (.-textContent target)
            selection (.getSelection js/window)
            is-at-start (and selection (= (.-anchorOffset selection) 0))]
        (when (or (empty? text-content) is-at-start)
          (.preventDefault e)
          (with-history!
            #(let [db @!db
                   focus (sel/get-focus db)
                   prev (get-in db [:derived :prev-id-of focus])
                   parent (get-in db [:derived :parent-of focus])]
               (when (and focus prev)
                 ;; If empty, just delete this block
                 (if (empty? text-content)
                   (do
                     (interpret! [{:op :place :id focus :under :trash :at :last}])
                     (update-db! sel/select prev))
                   ;; If at start with content, merge with previous
                   (let [prev-text (get-in db [:nodes prev :props :text] "")
                         merged-text (str prev-text text-content)]
                     (interpret! [{:op :update-node :id prev :props {:text merged-text}}
                                  {:op :place :id focus :under :trash :at :last}])
                     (update-db! sel/select prev))))))))

      ;; Delete selected blocks
      (and (= key "Backspace") (sel/has-selection? @!db))
      (do (.preventDefault e)
          (with-history!
            #(apply-intent! {:type :delete-selected})))

      ;; Collapse/Expand
      (and mod? (= key "ArrowUp"))
      (do (.preventDefault e)
          (js/console.log "Collapse (not implemented)"))

      (and mod? (= key "ArrowDown"))
      (do (.preventDefault e)
          (js/console.log "Expand (not implemented)"))

      ;; Escape - Exit edit mode (blur contentEditable)
      (= key "Escape")
      (do (.preventDefault e)
          (when-let [focused-elem (.-activeElement js/document)]
            (when (.-contentEditable focused-elem)
              (.blur focused-elem)))))))

;; ── Rendering ─────────────────────────────────────────────────────────────────

(defn render-block [db block-id depth]
  (let [block (get-in db [:nodes block-id])
        children (get-in db [:children-by-parent block-id] [])
        selected? (sel/selected? db block-id)
        focus? (= (sel/get-focus db) block-id)
        text (get-in block [:props :text] "")]
    [:div.block {:key block-id
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
                                 (update-db! sel/extend-selection block-id)
                                 (update-db! sel/select block-id)))}}
     [:span {:style {:margin-right "8px"}} "•"]
     [:span {:contentEditable true
             :suppressContentEditableWarning true
             :style {:outline "none"
                     :min-width "1px"  ; Ensure cursor is visible in empty blocks
                     :display "inline-block"}
             :data-block-id block-id
             :on {:input (fn [e]
                          (let [new-text (-> e .-target .-textContent)]
                            ;; Don't trigger re-render during typing to preserve cursor
                            (swap! !db assoc-in [:nodes block-id :props :text] new-text)))
                  :blur (fn [e]
                         ;; Save to history on blur
                         (update-db! H/record))
                  :focus (fn [e]
                          ;; Select block when text is focused
                          (update-db! sel/select block-id))}}
      text]
     (when (seq children)
       (into [:div {:style {:margin-top "2px"}}]
             (map #(render-block db % (inc depth)) children)))]))

(defn render-tree [db]
  (let [page-id "page"
        page-children (get-in db [:children-by-parent page-id] [])]
    [:div.tree {:style {:font-family "system-ui, -apple-system, sans-serif"
                        :padding "10px"}}
     [:h3 {:style {:margin-top 0}} "My Page"]
     (into [:div]
           (map #(render-block db % 0) page-children))]))

(defn render-debug [db]
  [:div {:style {:margin-top "30px"
                 :padding "15px"
                 :background-color "#f8f9fa"
                 :border-radius "4px"
                 :font-family "monospace"
                 :font-size "12px"}}
   [:div [:strong "Selection: "] (pr-str (sel/get-selection db))]
   [:div [:strong "Focus: "] (pr-str (sel/get-focus db))]
   [:div [:strong "Anchor: "] (pr-str (sel/get-anchor db))]
   [:div {:style {:margin-top "10px"}} [:strong "Can undo: "] (str (H/can-undo? db))]
   [:div [:strong "Can redo: "] (str (H/can-redo? db))]])

(defn render-hotkeys []
  [:div.hotkeys-footer
   [:div.hotkeys-header
    [:h4 "Keyboard Shortcuts (Logseq Style)"]
    [:span.hotkeys-hint "Click a block to select it"]]
   [:div.hotkeys-grid
    [:div.hotkey-group
     [:h5 "Selection"]
     [:div.hotkey-item
      [:span.hotkey-desc "Select block"]
      [:div.hotkey-keys [:kbd "Click"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Next block"]
      [:div.hotkey-keys [:kbd "↓"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Previous block"]
      [:div.hotkey-keys [:kbd "↑"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Extend down"]
      [:div.hotkey-keys [:kbd "Shift"] [:span.key-separator "+"] [:kbd "↓"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Extend up"]
      [:div.hotkey-keys [:kbd "Shift"] [:span.key-separator "+"] [:kbd "↑"]]]]

    [:div.hotkey-group
     [:h5 "Editing"]
     [:div.hotkey-item
      [:span.hotkey-desc "New block"]
      [:div.hotkey-keys [:kbd "Enter"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Newline in block"]
      [:div.hotkey-keys [:kbd "Shift"] [:span.key-separator "+"] [:kbd "Enter"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Exit edit mode"]
      [:div.hotkey-keys [:kbd "Esc"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Indent"]
      [:div.hotkey-keys [:kbd "Tab"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Outdent"]
      [:div.hotkey-keys [:kbd "Shift"] [:span.key-separator "+"] [:kbd "Tab"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Delete/Merge"]
      [:div.hotkey-keys [:kbd "Backspace"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Undo"]
      [:div.hotkey-keys [:kbd "⌘/Ctrl"] [:span.key-separator "+"] [:kbd "Z"]]]
     [:div.hotkey-item
      [:span.hotkey-desc "Redo"]
      [:div.hotkey-keys [:kbd "⌘/Ctrl"] [:span.key-separator "+"] [:kbd "Shift"] [:span.key-separator "+"] [:kbd "Z"]]]]]])

(defn render-app []
  (let [db @!db]
    [:div.app
     [:h2 "Structural Editing Demo"]
     [:p {:style {:color "#666"}} "Select blocks and use keyboard shortcuts to edit the structure."]
     (render-tree db)
     (render-debug db)
     (render-hotkeys)]))

;; ── Main ──────────────────────────────────────────────────────────────────────

(defn render! []
  (d/render (js/document.getElementById "root")
            (render-app)))

(defn main []
  (js/console.log "Blocks UI starting...")

  ;; Set up keyboard event listener
  (.addEventListener js/document "keydown" handle-keydown)

  ;; Set up auto-render on state changes
  (add-watch !db :render (fn [_ _ _ _] (render!)))

  (render!))
