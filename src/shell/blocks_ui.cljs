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
            [components.sidebar :as sidebar]
            [components.devtools :as devtools]
            [dataspex.core :as dataspex]
            [dev.tooling :as dev]
            [shell.nexus :as nexus]
            [plugins.selection]
            [plugins.editing]
            [plugins.clipboard] ;; Load to register paste/copy/cut intents
            [plugins.navigation] ;; Load to register navigation intents (cursor memory)
            [plugins.struct]
            [plugins.folding]
            [plugins.smart-editing]
            [plugins.text-formatting]
            [plugins.pages :as pages] ;; Load to register page intents
            [keymap.core :as keymap]
            [keymap.bindings :as bindings]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defn- test-mode?
  "Check if running in E2E test mode"
  []
  (when-let [search (.-search js/location)]
    (boolean (re-find #"[?&]test=true" search))))

(defonce !db
  (atom
   (if (test-mode?)
     ;; E2E test mode: empty database
     (-> (DB/empty-db)
         (H/record))
     ;; Normal mode: load demo content
     (-> (DB/empty-db)
               ;; Create multiple pages with transclusion examples
         (tx/interpret [;; ── Projects Page ──
                      {:op :create-node :id "projects" :type :page :props {:title "Projects"}}
                      {:op :place :id "projects" :under :doc :at :last}
                      {:op :create-node :id "proj-1" :type :block :props {:text "Evolver - Outliner Project"}}
                      {:op :place :id "proj-1" :under "projects" :at :last}
                      {:op :create-node :id "proj-1-1" :type :block :props {:text "Building a Logseq-inspired outliner"}}
                      {:op :place :id "proj-1-1" :under "proj-1" :at :last}
                      {:op :create-node :id "proj-1-2" :type :block :props {:text "Using event sourcing architecture"}}
                      {:op :place :id "proj-1-2" :under "proj-1" :at :last}
                      {:op :create-node :id "proj-2" :type :block :props {:text "Tech Stack: ClojureScript + Replicant"}}
                      {:op :place :id "proj-2" :under "projects" :at :last}
                      {:op :create-node :id "proj-3" :type :block :props {:text "See also: [[Tasks]] page for work items"}}
                      {:op :place :id "proj-3" :under "projects" :at :last}

                              ;; ── Tasks Page ──
                      {:op :create-node :id "tasks" :type :page :props {:title "Tasks"}}
                      {:op :place :id "tasks" :under :doc :at :last}
                      {:op :create-node :id "task-1" :type :block :props {:text "Implement block embeds"}}
                      {:op :place :id "task-1" :under "tasks" :at :last}
                      {:op :create-node :id "task-1-1" :type :block :props {:text "Parse embed syntax"}}
                      {:op :place :id "task-1-1" :under "task-1" :at :last}
                      {:op :create-node :id "task-1-2" :type :block :props {:text "Reference example: ((proj-2)) shows tech stack"}}
                      {:op :place :id "task-1-2" :under "task-1" :at :last}
                      {:op :create-node :id "task-1-3" :type :block :props {:text "Render full tree with children"}}
                      {:op :place :id "task-1-3" :under "task-1" :at :last}
                      {:op :create-node :id "task-2" :type :block :props {:text "Test embed here: {{embed ((proj-1))}}"}}
                      {:op :place :id "task-2" :under "tasks" :at :last}
                      {:op :create-node :id "task-3" :type :block :props {:text "Related project: [[Projects]]"}}
                      {:op :place :id "task-3" :under "tasks" :at :last}

                              ;; ── Notes Page ──
                      {:op :create-node :id "notes" :type :page :props {:title "Notes"}}
                      {:op :place :id "notes" :under :doc :at :last}
                      {:op :create-node :id "note-1" :type :block :props {:text "Block reference example: ((proj-2))"}}
                      {:op :place :id "note-1" :under "notes" :at :last}
                      {:op :create-node :id "note-2" :type :block :props {:text "This refs a task inline: ((task-1))"}}
                      {:op :place :id "note-2" :under "notes" :at :last}
                      {:op :create-node :id "note-3" :type :block :props {:text "Navigate between: [[Projects]], [[Tasks]], [[Notes]]"}}
                      {:op :place :id "note-3" :under "notes" :at :last}
                      {:op :create-node :id "note-4" :type :block :props {:text "Full embed of task tree:"}}
                      {:op :place :id "note-4" :under "notes" :at :last}
                      {:op :create-node :id "note-4-1" :type :block :props {:text "{{embed ((task-1))}}"}}
                      {:op :place :id "note-4-1" :under "note-4" :at :last}

                              ;; Set initial current page to Projects
                      {:op :update-node :id "session/ui" :props {:current-page "projects"}}])
       :db
       (H/record)))) ;; Record initial state for undo

;; ── Intent dispatcher ─────────────────────────────────────────────────────────

(defn handle-intent
  "Single intent dispatcher - handles both intent maps and Nexus action vectors.

   This is the ONLY place intents are dispatched.
   Components call (on-intent {:type ...}) for intents or (on-intent [[:action/name ...]]) for Nexus actions."
  [intent-or-actions]
  (cond
    ;; Nexus action vector: dispatch through Nexus
    (vector? intent-or-actions)
    (nexus/dispatch! !db {} intent-or-actions)

    ;; Intent map: dispatch directly through kernel
    (map? intent-or-actions)
    (do
      (js/console.log "Intent:" (pr-str intent-or-actions))
      (swap! !db (fn [db]
                   (let [db-before db
                         {:keys [db issues]} (api/dispatch db intent-or-actions)
                         db-after db
                         should-log? (not (contains? #{:inspect-dataspex :clear-log} (:type intent-or-actions)))]
                     (when (seq issues)
                       (js/console.error "Intent validation failed:" (pr-str issues)))
                     (when should-log?
                       (dev/log-dispatch! intent-or-actions db-before db-after))
                     db))))

    ;; Keyword: wrap in :type map
    :else
    (handle-intent {:type intent-or-actions})))

;; ── Global keyboard shortcuts (Keymap Resolver) ───────────────────────────────

(defn handle-global-keydown [e]
  "Global keyboard shortcuts via central keymap resolver.

   Single source of truth: keymap/bindings.cljc registers all bindings.
   This function just resolves key event → intent type → dispatch.

   NOTE: Arrow key navigation at block boundaries is handled by the Block component
   (see components/block.cljs handle-arrow-up/down) using cursor row detection,
   NOT here. The block component dispatches :navigate-with-cursor-memory intents."
  (let [event (keymap/parse-dom-event e)
        db @!db
        key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        focus-id (q/focus db)
        editing? (q/editing-block-id db)
        idle? (and (nil? editing?) (nil? focus-id))  ; FR-Idle-01: True idle state
        intent-type (keymap/resolve-intent-type event db)

        ;; Editable element for text formatting
        editable-el (when editing? (.-activeElement js/document))

        ;; Printable character check for "start typing to edit" behavior
        printable? (and (= 1 (.-length key))
                        (not (:mod event))
                        (not (:alt event))
                        (not (contains? #{"Enter" "Escape" "Tab" "Backspace" "Delete"} key)))]

    (cond
      ;; FR-Idle-01: Idle guard - no accidental edits
      ;; In true idle state (no block selected, no block editing), Enter/Backspace/Tab/etc are no-ops
      (and idle?
           intent-type
           (contains? #{"Enter" "Backspace" "Delete" "Tab"} key))
      nil ;; No-op in idle state

      ;; FR-Idle-01: Guard Cmd+Enter in idle state
      (and idle?
           mod?
           (= key "Enter"))
      nil ;; No-op in idle state

      ;; FR-Idle-01: Guard Shift+Enter in idle state
      (and idle?
           shift?
           (= key "Enter"))
      nil ;; No-op in idle state

      ;; FR-Idle-01: Guard Shift+Arrow in idle state
      (and idle?
           shift?
           (contains? #{"ArrowUp" "ArrowDown"} key))
      nil ;; No-op in idle state

      ;; NOTE: Arrow key navigation removed - handled by Block component with cursor row detection

      ;; Skip Shift+Arrow when editing - let Block component handle text selection
      ;; (LOGSEQ_SPEC §3 Rule 3: Shift+Arrow extends text selection within block)
      (and intent-type
           editing?
           shift?
           (contains? #{"ArrowUp" "ArrowDown"} key)
           (not mod?) ;; Only plain Shift+Arrow, not Cmd+Shift+Arrow (move blocks)
           (not (.-altKey e)))
      nil ;; Let event bubble to Block component

      ;; Keymap-resolved intent
      intent-type
      (do (.preventDefault e)
          (cond
            ;; Undo/Redo - special handling (modify DB directly, not via operations)
            (= intent-type :undo)
            (swap! !db (fn [db] (or (H/undo db) db)))

            (= intent-type :redo)
            (swap! !db (fn [db] (or (H/redo db) db)))

            ;; All other intents - go through normal intent dispatch
            :else
            (let [;; Inject focused block-id for fold/zoom intents
                  intent-with-focus (if (and (map? intent-type)
                                             (#{:toggle-fold :collapse :expand-all :zoom-in} (:type intent-type))
                                             focus-id)
                                      (assoc intent-type :block-id focus-id)
                                      intent-type)
                  ;; Replace :editing-block-id placeholder with actual editing block ID
                  intent-with-id (if (and (map? intent-with-focus)
                                          (= (:block-id intent-with-focus) :editing-block-id)
                                          editing?)
                                   (assoc intent-with-focus :block-id editing?)
                                   intent-with-focus)
                  ;; Enrich format-selection intent with DOM selection data
                  enriched-intent (cond
                                    ;; Format-selection: get DOM selection range
                                    (and (map? intent-with-id)
                                         (= (:type intent-with-id) :format-selection)
                                         editing?
                                         editable-el)
                                    (try
                                      (let [sel (.getSelection js/window)]
                                        (when (and sel (pos? (.-rangeCount sel)))
                                          (let [range (.getRangeAt sel 0)
                                                start (.-startOffset range)
                                                end (.-endOffset range)]
                                            (when (not= start end) ;; Only if there's actual selection
                                              (merge intent-with-id
                                                     {:block-id editing?
                                                      :start start
                                                      :end end})))))
                                      (catch js/Error e
                                        (js/console.error "Selection read failed:" e)
                                        nil)) ;; Return nil if enrichment fails

                                    ;; Follow-link-under-cursor: inject cursor position
                                    (and (map? intent-with-id)
                                         (= (:type intent-with-id) :follow-link-under-cursor)
                                         (= (:cursor-pos intent-with-id) :cursor-pos)
                                         editing?
                                         editable-el)
                                    (try
                                      (let [sel (.getSelection js/window)]
                                        (when sel
                                          (let [cursor-pos (.-anchorOffset sel)]
                                            (assoc intent-with-id :cursor-pos cursor-pos))))
                                      (catch js/Error e
                                        (js/console.error "Cursor position read failed:" e)
                                        nil))

                                    ;; Default: no enrichment needed
                                    :else intent-with-id)]
              (when enriched-intent ;; Only dispatch if enrichment succeeded
                (cond
                ;; Map intent: use directly (with injected block-id if needed)
                  (map? enriched-intent)
                  (handle-intent enriched-intent)

                ;; Keyword intent: wrap in :type
                  :else
                  (handle-intent {:type enriched-intent}))))))

      ;; Printable character - Enter edit mode (Logseq-style "start typing")
      (and printable? focus-id (not editing?))
      (handle-intent {:type :enter-edit :block-id focus-id}))))

;; ── Rendering ─────────────────────────────────────────────────────────────────

(defn MockText
  "Hidden element for cursor position detection (Logseq technique).
   
   CRITICAL: Must match contenteditable styling for accurate row detection:
   - word-wrap and overflow-wrap must match to ensure same wrapping behavior
   - width, font-size, font-family, line-height copied dynamically by update-mock-text!"
  []
  [:div#mock-text
   {:style {:width "100%"
            :height "100%"
            :position "absolute"
            :visibility "hidden"
            :top 0
            :left 0
            :pointer-events "none"
            :z-index -1000
            :word-wrap "break-word" ;; Match contenteditable
            :overflow-wrap "break-word"}}]) ;; Match contenteditable

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

(defn HotkeysReference []
  [:div.hotkeys-footer
   {:style {:margin-top "30px"
            :padding "20px"
            :background-color "#f0f0f0"
            :border-radius "4px"}}
   [:h4 "Keyboard Shortcuts (Logseq Style)"]
   [:div {:style {:display "grid"
                  :grid-template-columns "repeat(3, 1fr)"
                  :gap "10px"}}
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
  (let [db @!db
        current-page-id (pages/current-page db)
        page-title (when current-page-id (pages/page-title db current-page-id))]
    [:div.app
     {:style {:display "flex"
              :min-height "100vh"}}

     ;; Sidebar for page navigation
     (sidebar/Sidebar {:db db :on-intent handle-intent})

     ;; Main content area
     [:div.main-content
      {:style {:flex "1"
               :margin-left "220px" ; Offset for fixed sidebar
               :font-family "system-ui, -apple-system, sans-serif"
               :padding "20px"
               :max-width "800px"}
       ;; Background click to clear selection (Logseq parity)
       ;; Blocks call stopPropagation, so this only fires for empty background clicks
       :on {:click (fn [e]
                     ;; Only clear if not editing and clicking empty background
                     (when-not (q/editing-block-id db)
                       (handle-intent {:type :selection :mode :clear})))}}

      ;; Mock-text for cursor detection
      (MockText)

      [:h2 "Blocks UI - Multi-Page Demo"]
      [:p {:style {:color "#666"}}
       "Features: Block refs " [:code "((id))"] ", Embeds " [:code "{{embed ((id))}}"] ", Page refs " [:code "[[Page]]"]]

      ;; Current page title and outline
      (if current-page-id
        [:div
         [:h3 {:style {:margin-top "20px"
                       :margin-bottom "10px"
                       :color "rgb(29, 78, 216)"}}
          "📄 " page-title]

         ;; Main outline for current page only
         (Outline {:db db
                   :root-id current-page-id
                   :on-intent handle-intent})]

        ;; No page selected
        [:div {:style {:padding "40px"
                       :text-align "center"
                       :color "rgb(156, 163, 175)"}}
         [:p "Select a page from the sidebar to begin"]])

      ;; Dev tools (Simplified: Event → Human-Spec → DB Diff)
      (devtools/DevToolsPanel {:db db})

      ;; Hotkeys reference
      (HotkeysReference)]]))

;; ── Main ──────────────────────────────────────────────────────────────────────

(defn render! []
  (d/render (js/document.getElementById "root")
            (App)))

;; ── Test Helpers ──────────────────────────────────────────────────────────────

(defn reset-to-empty-db!
  "Reset database to empty state for E2E tests.
   Exposed on window.TEST_HELPERS for Playwright."
  []
  (reset! !db (-> (DB/empty-db)
                  (H/record))))

(defn main []
  (js/console.log "Blocks UI starting with proper architecture...")

  ;; Expose test helpers for E2E tests
  (set! (.-TEST_HELPERS js/window)
        #js {:resetToEmptyDb reset-to-empty-db!})

  ;; Initialize keyboard bindings (explicit, not side-effect)
  (bindings/reload!)

  ;; Initialize Nexus action pipeline
  (nexus/init!)

  ;; Enable lifecycle hooks + Nexus dispatch
  ;; CRITICAL: Lifecycle hooks must still fire for cursor placement
  (d/set-dispatch!
   (fn [event-data handler-data]
     (cond
       ;; Handle lifecycle hooks
       (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
       (when (fn? handler-data)
         (handler-data event-data))

       ;; Handle DOM events via Nexus
       (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (cond
         ;; Data-driven Nexus actions (preferred)
         (vector? handler-data)
         (nexus/dispatch! !db event-data handler-data)

         ;; Legacy function-based handlers (temporary during migration)
         (fn? handler-data)
         (handler-data (:replicant/dom-event event-data))))))

  ;; Set up global keyboard listener (Cmd+Z, etc)
  (.addEventListener js/document "keydown" handle-global-keydown)

  ;; Set up auto-render on state changes
  (add-watch !db :render (fn [_ _ _ _] (render!)))

  ;; Initialize Dataspex for DB inspection
  (dataspex/inspect "App DB" !db {:track-changes? true})

  ;; Apply text selection effects from formatting operations
  (add-watch !db :text-selection-effects
             (fn [_ _ _ new-db]
               (when-let [{:keys [block-id start end]}
                          (get-in new-db [:nodes "session/ui" :props :pending-selection])]
                 (js/requestAnimationFrame
                  (fn []
                    (when-let [editable-el (.querySelector js/document
                                                           (str "[data-block-id='" block-id "'].content-edit"))]
                      (try
                        (let [text-node (.-firstChild editable-el)
                              sel (.getSelection js/window)
                              range (.createRange js/document)]
                          (when (and text-node (= (.-nodeType text-node) 3))
                            (.setStart range text-node start)
                            (.setEnd range text-node end)
                            (.removeAllRanges sel)
                            (.addRange sel range)))
                        (catch js/Error e
                          (js/console.error "Text selection failed:" e))))
                    ;; Clear pending selection after applying
                    (swap! !db assoc-in [:nodes "session/ui" :props :pending-selection] nil))))))

  (render!))
