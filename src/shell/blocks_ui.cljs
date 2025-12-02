(ns shell.blocks-ui
  "Blocks UI demo - composition layer only.

   Demonstrates proper architecture:
   - Plugins provide getters and extend intent multimethods
   - Components use getters and dispatch intents
   - App just composes components and routes intents"
  (:require-macros [shell.plugin-manifest :refer [require-specs]])
  (:require [clojure.string :as str]
            [replicant.dom :as d]
            [kernel.db :as DB]
            [kernel.api :as api]
            [kernel.query :as q]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [kernel.constants :as const]
            [components.block :as block]
            [components.sidebar :as sidebar]
            [components.devtools :as devtools]
            [dataspex.core :as dataspex]
            [dev.tooling :as dev]
            [shell.nexus :as nexus]
            [shell.demo-data :as demo-data]
            [shell.e2e-scenarios]
            [shell.plugin-manifest-runtime :as plugin-manifest]
            [shell.session :as session]
            ;; Phases 4 & 5: session-sync removed - session is source of truth
            ;; Load all plugins to register intents
            [plugins.selection]
            [plugins.editing]
            [plugins.clipboard]
            [plugins.navigation]
            [plugins.struct]
            [plugins.folding]
            [plugins.smart-editing]
            [plugins.text-formatting]
            [plugins.visible-order]
            ;; Phase 3: [plugins.buffer] removed - buffer now purely in session
            [plugins.pages :as pages]
            [keymap.core :as keymap]
            [keymap.bindings :as bindings]
            [kernel.state-machine :as sm]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defn- test-mode?
  "Check if running in E2E test mode"
  []
  (let [search (.-search js/location)
        has-param (and search (>= (.indexOf search "test=true") 0))]
    (js/console.log "TEST MODE CHECK - search:" search "has-param:" has-param)
    (boolean has-param)))

(defn- devtools-enabled?
  "Check if devtools UI should be shown (via ?devtools query param)"
  []
  (let [search (.-search js/location)]
    (boolean (and search (>= (.indexOf search "devtools") 0)))))

(defonce !db
  (atom
   (if (test-mode?)
     ;; E2E test mode: empty database
     (-> (DB/empty-db)
         (H/record))
     ;; Normal mode: load demo content
     (-> (DB/empty-db)
         (tx/interpret demo-data/ops)
         :db
         (H/record))))) ;; Record initial state for undo

;; ── Intent dispatcher ─────────────────────────────────────────────────────────

(defn- assert-derived-fresh!
  "DEBUG: Assert that derived indexes are consistent after DB reset.
   Throws if inconsistent to immediately catch the corruption source."
  [db label]
  (when ^boolean goog.DEBUG
    (when-let [inconsistency (DB/check-parent-of-consistency db)]
      (js/console.error "🚨🚨🚨 DERIVED INDEX CORRUPTION DETECTED 🚨🚨🚨"
                        "\nLabel:" label
                        "\nInconsistency:" (pr-str inconsistency)
                        "\nDB hash:" (hash db)
                        "\nchildren-by-parent keys:" (pr-str (keys (:children-by-parent db))))
      ;; Log stack trace to find the caller
      (js/console.trace "Stack trace for corruption detection"))))

(defn handle-intent
  "Single intent dispatcher - handles both intent maps and Nexus action vectors.

   This is the ONLY place intents are dispatched.
   Components call (on-intent {:type ...}) for intents or (on-intent [[:action/name ...]]) for Nexus actions.

   Phase 6: Passes session to api/dispatch and applies session-updates."
  [intent-or-actions]
  (cond
    ;; Nexus action vector: dispatch through Nexus
    (vector? intent-or-actions)
    (nexus/dispatch! !db {} intent-or-actions)

    ;; Intent map: dispatch directly through kernel
    (map? intent-or-actions)
    (let [intent-type (:type intent-or-actions)
          ;; DEBUG: Log ALL direct intent dispatches
          _ (js/console.log "🔶 DIRECT dispatch:" (pr-str intent-type)
                            "- DB hash:" (hash @!db))
          ;; Structural operations that may cause DOM re-render and blur
          structural? (contains? #{:indent-selected :outdent-selected
                                   :move-selected-up :move-selected-down
                                   :delete-selected} intent-type)
          _ (when structural?
              ;; Suppress blur-exit during structural ops to prevent focus loss during re-render
              (session/suppress-blur-exit!))
          current-session (session/get-session)
          db-before @!db
          result (api/dispatch db-before current-session intent-or-actions)
          db-after (:db result)
          issues (:issues result)
          session-updates (:session-updates result)
          should-log? (not (contains? #{:inspect-dataspex :clear-log} intent-type))]

      ;; Report any validation issues
      (when (seq issues)
        (js/console.error "Intent validation failed:" (pr-str issues)))

;; Apply DB changes
      (reset! !db db-after)

      ;; DEBUG: Assert derived indexes are fresh after reset
      (assert-derived-fresh! db-after (str "after DIRECT dispatch: " intent-type))

      ;; Apply session updates if any (Phase 6)
      (when session-updates
        (session/swap-session! #(merge-with merge % session-updates)))

      ;; Log dispatch for devtools
      (when should-log?
        (dev/log-dispatch! intent-or-actions db-before db-after)))

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
        current-session (session/get-session)
        key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        focus-id (session/focus-id)
        editing? (session/editing-block-id)
        idle? (and (nil? editing?) (nil? focus-id)) ; FR-Idle-01: True idle state
        intent-type (keymap/resolve-intent-type event current-session)

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
            ;; LOGSEQ PARITY (FR-Undo-01): Restore cursor/editing state to session
            (= intent-type :undo)
            (let [new-db (H/undo @!db)]
              (when new-db
                (reset! !db new-db)
                ;; DEBUG: Assert derived indexes are fresh after undo
                (assert-derived-fresh! new-db "after undo")
                ;; Restore editing state from historical snapshot to session
                (let [editing-id (get-in new-db [:nodes const/session-ui-id :props :editing-block-id])
                      cursor-pos (get-in new-db [:nodes const/session-ui-id :props :cursor-position])]
                  (session/swap-session!
                   (fn [s]
                     (-> s
                         (assoc-in [:ui :editing-block-id] editing-id)
                         (assoc-in [:ui :cursor-position] cursor-pos)
                         ;; Clear selection when restoring edit state
                         (assoc-in [:selection :nodes] (if editing-id #{} (:nodes (:selection s))))
                         (assoc-in [:selection :focus] (when-not editing-id (:focus (:selection s))))))))))

            (= intent-type :redo)
            (let [new-db (H/redo @!db)]
              (when new-db
                (reset! !db new-db)
                ;; DEBUG: Assert derived indexes are fresh after redo
                (assert-derived-fresh! new-db "after redo")
                ;; Restore editing state from future snapshot to session
                (let [editing-id (get-in new-db [:nodes const/session-ui-id :props :editing-block-id])
                      cursor-pos (get-in new-db [:nodes const/session-ui-id :props :cursor-position])]
                  (session/swap-session!
                   (fn [s]
                     (-> s
                         (assoc-in [:ui :editing-block-id] editing-id)
                         (assoc-in [:ui :cursor-position] cursor-pos)
                         ;; Clear selection when restoring edit state
                         (assoc-in [:selection :nodes] (if editing-id #{} (:nodes (:selection s))))
                         (assoc-in [:selection :focus] (when-not editing-id (:focus (:selection s))))))))))

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

      ;; Printable character - Enter edit mode AND append character (Logseq-style "start typing")
      ;; LOGSEQ PARITY §7.1: pressing any printable key instantly enters edit mode,
      ;; appends that character, and positions the caret after it
      (and printable? focus-id (not editing?))
      (do
        (.preventDefault e)
        (handle-intent {:type :enter-edit-with-char :block-id focus-id :char key})))))

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
  (let [children (get-in db [:children-by-parent root-id] [])
        editing-block-id (session/editing-block-id)
        focus-block-id (session/focus-id)
        selection-set (session/selection-nodes)
        folded-set (session/folded)]
    (into [:div.outline]
          (map (fn [child-id]
                 (block/Block {:db db
                               :block-id child-id
                               :depth 0
                               :is-focused (= focus-block-id child-id)
                               :is-selected (contains? selection-set child-id)
                               :is-editing (= editing-block-id child-id)
                               :is-folded (contains? folded-set child-id)
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
        current-page-id (session/current-page)
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
                     (when-not (session/editing-block-id)
                       (handle-intent {:type :selection :mode :clear})))}}

      ;; Mock-text for cursor detection
      (MockText)

      [:h2 "Blocks UI - Multi-Page Demo"]
      [:p {:style {:color "#666"}}
       "Features: Page refs " [:code "[[Page]]"]]

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
      ;; Auto-show when ?devtools query param present
      (when (devtools-enabled?)
        (devtools/DevToolsPanel {:db db}))

      ;; Hotkeys reference
      (HotkeysReference)]]))

;; ── Main ──────────────────────────────────────────────────────────────────────

(defn render! []
  (d/render (js/document.getElementById "root")
            (App)))

;; Batched render using requestAnimationFrame to prevent nested render warnings.
;; When multiple state changes (DB + session) happen in the same frame,
;; this coalesces them into a single render call.
(defonce ^:private render-scheduled? (atom false))

(defn request-render!
  "Request a render on the next animation frame.
   Multiple calls in the same frame are coalesced into one render."
  []
  (when-not @render-scheduled?
    (reset! render-scheduled? true)
    (js/requestAnimationFrame
     (fn []
       (reset! render-scheduled? false)
       (render!)))))

;; ── Test Helpers ──────────────────────────────────────────────────────────────

(defn reset-to-empty-db!
  "Reset database to empty state for E2E tests with one empty block.
   Exposed on window.TEST_HELPERS for Playwright."
  []
  ;; Reset DB (document only, no session data)
  (reset! !db (-> (DB/empty-db)
                  (tx/interpret [{:op :create-node :id "test-page" :type :page :props {:title "Test Page"}}
                                 {:op :place :id "test-page" :under :doc :at :last}
                                 {:op :create-node :id "test-block-1" :type :block :props {:text ""}}
                                 {:op :place :id "test-block-1" :under "test-page" :at :last}])
                  :db
                  (H/record)))
  ;; Reset session and set current page
  (session/reset-session!)
  (session/swap-session! assoc-in [:ui :current-page] "test-page"))

(defn main []
  (js/console.log "Blocks UI starting with proper architecture...")
  (js/console.log "Current URL:" (.-href js/location))

  ;; Reset to empty DB for E2E tests (handles hot-reload where defonce doesn't re-run)
  (if (test-mode?)
    (do
      (js/console.log "Test mode detected - resetting to empty DB")
      (reset-to-empty-db!)
      (js/console.log "DB after reset - block count:" (count (get-in @!db [:nodes]))))
    ;; Normal mode: auto-select first page (Projects) for demo data
    (do
      (js/console.log "Normal mode - auto-selecting Projects page")
      (session/swap-session! assoc-in [:ui :current-page] "projects")))

  ;; Expose test helpers for E2E tests
  (set! (.-TEST_HELPERS js/window)
        #js {:resetToEmptyDb reset-to-empty-db!
             :dispatchIntent (fn [intent-js]
                              ;; Convert JS object to Clojure map, ensuring keyword fields are keywords
                               (let [raw (js->clj intent-js :keywordize-keys true)
                                    ;; Fields that need keyword values (not just keys)
                                     intent (cond-> raw
                                              (:type raw) (update :type keyword)
                                              (:mode raw) (update :mode keyword)
                                              (:at raw) (update :at keyword)
                                              (:cursor-at raw) (update :cursor-at keyword))]
                                 (handle-intent intent)))
             ;; Direct DB manipulation for test fixture setup
             ;; Bypasses state machine (appropriate for setting initial state)
             :setBlockText (fn [block-id text]
                             (swap! !db assoc-in [:nodes block-id :props :text] text))
             :getBlockText (fn [block-id]
                             (get-in @!db [:nodes block-id :props :text] ""))
             :getDb (fn [] (clj->js @!db))
             :getSession (fn [] (clj->js (session/get-session)))
             ;; Transact raw ops (for test setup - creates blocks, places them, etc.)
             :transact (fn [ops-js]
                         (let [ops (js->clj ops-js :keywordize-keys true)
                              ;; Convert special string values to keywords
                               ops (mapv (fn [op]
                                           (cond-> op
                                             (:op op) (update :op keyword)
                                             (:type op) (update :type keyword)
                                             (:at op) (update :at keyword)
                                            ;; Handle :under as keyword for special roots
                                             (and (:under op) (string? (:under op))
                                                  (#{"doc" ":doc" "trash" ":trash"} (:under op)))
                                             (update :under #(keyword (str/replace % #"^:" "")))))
                                         ops)
                               result (tx/interpret @!db ops)]
                           (reset! !db (H/record (:db result)))))

             ;; ── Debug Helpers (for E2E test diagnostics) ────────────────────────
             ;; Debug an intent - check if it would be allowed and why
             :debugIntent (fn [intent-js]
                            (let [raw (js->clj intent-js :keywordize-keys true)
                                  intent (cond-> raw
                                           (:type raw) (update :type keyword)
                                           (:mode raw) (update :mode keyword))
                                  current-session (session/get-session)
                                  state (sm/current-state current-session)
                                  allowed? (sm/intent-allowed? current-session intent)
                                  requirements (get sm/intent-state-requirements (:type intent))]
                              #js {:allowed allowed?
                                   :currentState (name state)
                                   :intentType (name (:type intent))
                                   :requiredStates (when requirements
                                                     (clj->js (mapv name requirements)))
                                   :reason (when-not allowed?
                                             (str "Intent :" (:type intent)
                                                  " requires states " (pr-str requirements)
                                                  " but current state is :" state))}))

             ;; Get a snapshot of current app state for debugging
             :snapshot (fn []
                         (let [current-session (session/get-session)]
                           #js {:state (name (sm/current-state current-session))
                                :editingBlockId (get-in current-session [:ui :editing-block-id])
                                :selectedIds (clj->js (vec (get-in current-session [:selection :nodes] #{})))
                                :focusId (get-in current-session [:selection :focus])
                                :bufferBlockId (get-in current-session [:buffer :block-id])
                                :bufferDirty (get-in current-session [:buffer :dirty?])}))})

  ;; Phase 2: Expose session for debugging
  (set! (.-SESSION js/window) session/!session)

  ;; Initialize keyboard bindings (explicit, not side-effect)
  (bindings/reload!)

  ;; Initialize Nexus action pipeline
  (nexus/init!)

  ;; Phases 4 & 5: Session is independent, no init from DB needed
  (js/console.log "Session initialized (independent of DB)")

  ;; Set initial current page to first page (for navigation scope)
  ;; Fixes G-Nav-Visibility: navigation must respect current page boundary
  (when-not (test-mode?)
    (let [first-page (first (q/children @!db :doc))]
      (when first-page
        (session/swap-session! assoc-in [:ui :current-page] first-page)
        (js/console.log "Initial page set to:" first-page))))

  (js/console.log "Registered plugins:" (clj->js plugin-manifest/manifest))

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

  ;; Set up auto-render on state changes (both DB and session)
  ;; Uses request-render! to batch multiple changes into single render (prevents nested render warnings)
  (add-watch !db :render (fn [_ _ _ _] (request-render!)))
  (add-watch session/!session :render (fn [_ _ _ _] (request-render!)))

  ;; Initialize Dataspex for DB inspection
  (dataspex/inspect "App DB" !db {:track-changes? true})

  ;; Apply text selection effects from formatting operations
  ;; Watch session for pending-selection instead of DB
  (add-watch session/!session :text-selection-effects
             (fn [_ _ _ new-session]
               (when-let [{:keys [block-id start end]}
                          (get-in new-session [:ui :pending-selection])]
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
                    (session/swap-session! assoc-in [:ui :pending-selection] nil))))))

  (render!))
