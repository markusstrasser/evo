(ns shell.editor
  "Blocks UI composition layer.

   Responsibilities:
   - boot explicit startup surfaces (plugins, keymaps, storage, render)
   - compose components and route intents through the shared executor
   - keep DOM-global listeners and browser wiring at the shell edge"
  (:require [clojure.string :as str]
            [replicant.dom :as d]
            [kernel.db :as db]
            [kernel.query :as q]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [shell.log :as slog]
            [components.block :as block]
            [components.sidebar :as sidebar]
            [components.devtools :as devtools]
            [components.backlinks :as backlinks]
            [dataspex.core :as dataspex]
            [components.image :as image]
            [shell.dispatch-bridge :as dispatch-bridge]
            [shell.executor :as executor]
            [shell.global-keyboard :as global-keyboard]
            [shell.storage :as storage]
            [shell.e2e-scenarios]
            [shell.view-state :as vs]
            [shell.url-sync :as url-sync]
            [utils.text-selection :as text-sel]
            [debug-api]
            [plugins.manifest :as plugins]
            #_{:clj-kondo/ignore [:unused-namespace]} ; load-time registration
            [shell.render-manifest :as render-manifest]
            [keymap.bindings :as bindings]
            [kernel.state-machine :as sm]
            [kernel.intent :as intent]
            [components.spec-viewer :as spec-viewer]
            [components.quick-switcher :as quick-switcher]
            [components.notification :as notification]
            [components.lightbox :as lightbox]
            [components.journals :as journals]
            [components.all-pages :as all-pages]
            [utils.journal :as journal]
            [utils.cursor-boundaries :as cursor-bounds]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defn- query-param?
  "Check if URL contains a query param substring."
  [param]
  (let [search (.-search js/location)]
    (boolean (and search (>= (.indexOf search param) 0)))))

(defn- test-mode? [] (query-param? "test=true"))
(defn- devtools-enabled? [] (query-param? "devtools"))
(defn- specs-mode? [] (query-param? "specs"))
(defn- embed-mode? [] (query-param? "embed"))

;; Initial DB - starts with demo content, replaced when folder is loaded
(defonce !db
  (atom
   ;; Always start with empty DB - demo data loaded only if no folder configured.
   ;; Undo/redo lives in shell.log/!log (the canonical event-sourced state).
   (db/empty-db)))

;; Storage status atom for UI feedback
(defonce !storage-status
  (atom {:folder-name nil
         :loading? false
         ;; Start in checking state - true until we know if folder exists
         :checking? true}))

(defn- navigate-to-startup-page!
  "Navigate to initial surface based on URL param or default to the journals view.

   Priority:
   1. If ?page=PageName in URL, navigate to that page
   2. Otherwise, open the stacked journals view (Logseq-style homepage).
      The JournalsView component auto-materializes today's journal when
      missing via :ensure-page-exists, so no page creation is needed here."
  []
  (if-let [url-page-name (url-sync/get-page-from-url)]
    ;; URL specifies a page - navigate there
    (do
      (js/console.log "🔗 Opening page from URL:" url-page-name)
      (executor/apply-intent! !db
                              {:type :navigate-to-page
                               :page-name url-page-name}
                              "URL"))
    ;; No URL page - default to journals view (stacked, newest first)
    (do
      (js/console.log "📖 Opening journals view")
      (executor/apply-intent! !db
                              {:type :open-journals-view}
                              "STARTUP"))))

(defn load-from-folder!
  "Load pages from the currently selected folder into DB.
   If folder is empty, starts with empty DB.
   Navigates to page from URL or today's journal."
  []
  (lightbox/hide!)
  (swap! !storage-status assoc :loading? true)
  (-> (storage/load-all-pages)
      (.then (fn [ops]
               (if (seq ops)
                 ;; Folder has pages - load them. Loaded state is the new
                 ;; baseline (:root-db of the log), not an undoable action.
                 (do
                   (js/console.log "📂 Loading" (count ops) "ops from folder...")
                   (let [loaded (:db (tx/interpret (db/empty-db) ops))]
                     (reset! !db loaded)
                     (slog/reset-with-db! loaded)))
                 ;; Empty folder - start with empty DB
                 (do
                   (js/console.log "📂 Empty folder, starting fresh")
                   (reset! !db (db/empty-db))
                   (slog/reset-with-db! (db/empty-db))))
               ;; Navigate to startup page (URL param or today's journal)
               (navigate-to-startup-page!)
               (swap! !storage-status assoc
                      :loading? false
                      :checking? false
                      :folder-name (storage/get-folder-name))))
      (.catch (fn [err]
                (js/console.error "Failed to load from folder:" err)
                (swap! !storage-status assoc :loading? false :checking? false)))))

(defn pick-folder!
  "Show folder picker dialog and load pages from selected folder."
  []
  (-> (storage/pick-folder!)
      (.then (fn [handle]
               (when handle
                 (load-from-folder!))))))

(defn clear-folder!
  "Disconnect from the current folder and reset to empty state."
  []
  (lightbox/hide!)
  (storage/clear-folder!)
  (image/clear-url-cache!)
  (swap! !storage-status assoc :folder-name nil)
  ;; Reset to empty DB (no demo data - user must pick folder or start fresh)
  (reset! !db (db/empty-db))
  (slog/reset-with-db! (db/empty-db))
  (vs/set-current-page! nil))

;; Try to restore previously selected folder on startup
(defonce _restore-folder
  (when-not (test-mode?)
    (-> (storage/restore-folder!)
        (.then (fn [restored?]
                 (if restored?
                   ;; Folder found - load-from-folder! will clear :checking? when done
                   (load-from-folder!)
                   ;; No folder configured - clear checking state, stay empty
                   (swap! !storage-status assoc :checking? false))))
        (.catch (fn [_err]
                  ;; Error checking - clear state, stay empty
                  (swap! !storage-status assoc :checking? false))))))

;; Auto-save to folder on DB changes (debounced, page-scoped)
(defonce ^:private save-timeout (atom nil))
(defonce ^:private dirty-pages (atom #{}))

(defn- page-for
  "Return the enclosing page id for node-id in db, or node-id itself if it is
   a page. Returns nil for keyword roots and nodes with no page ancestor."
  [db node-id]
  (when (string? node-id)
    (if (= :page (get-in db [:nodes node-id :type]))
      node-id
      (q/page-of db node-id))))

(defn- dirty-page-ids
  "Compute the set of page ids whose on-disk representation may have changed
   between old-db and new-db. Looks at node-level and children-order diffs
   only; resolves each changed id to its enclosing page in BOTH dbs so moves,
   deletions, and page renames are covered."
  [old-db new-db]
  (let [old-nodes (:nodes old-db)
        new-nodes (:nodes new-db)
        old-children (:children-by-parent old-db)
        new-children (:children-by-parent new-db)
        diff-keys (fn [a b]
                    (into #{}
                          (concat
                           (keep (fn [[k v]] (when (not= v (get b k)) k)) a)
                           (keep (fn [[k v]] (when (not= v (get a k)) k)) b))))
        changed-ids (into (diff-keys old-nodes new-nodes)
                          (diff-keys old-children new-children))]
    (into #{}
          (comp (mapcat (fn [id] [(page-for old-db id) (page-for new-db id)]))
                (filter some?))
          changed-ids)))

(defn- schedule-save!
  "Accumulate dirty pages and schedule a debounced write (500ms)."
  [old-val new-val]
  (swap! dirty-pages into (dirty-page-ids old-val new-val))
  (when-let [t @save-timeout]
    (js/clearTimeout t))
  (reset! save-timeout
          (js/setTimeout
           (fn []
             (let [pages @dirty-pages]
               (reset! dirty-pages #{})
               (when (and (not (test-mode?)) (storage/has-folder?))
                 (storage/save-pages! new-val pages))))
           500)))

(defonce _db-watcher
  (add-watch !db :auto-save
             (fn [_ _ old-val new-val]
               (schedule-save! old-val new-val))))

;; ── URL Sync (popstate handler for browser back/forward) ────────────────────

(defn- handle-url-navigation
  "Handle browser back/forward navigation (popstate).
   Navigates to page specified in URL, or to journals view if none."
  [page-name]
  (if page-name
    ;; Navigate to page from URL
    (executor/apply-intent! !db
                            {:type :navigate-to-page
                             :page-name page-name}
                            "POPSTATE")
    ;; No page in URL - go to journals view
    (executor/apply-intent! !db
                            {:type :go-to-journal
                             :journal-title (journal/today-title)}
                            "POPSTATE")))

(defonce _url-sync-init
  (when-not (test-mode?)
    (url-sync/init! handle-url-navigation)))

;; ── Intent dispatcher ─────────────────────────────────────────────────────────

(def ^:private structural-intents
  "Intent types that may cause DOM re-render and blur.
   These intents need keep-edit-on-blur! to prevent the blur handler from
   exiting edit mode when focus shifts during re-render."
  #{:indent-selected :outdent-selected :move-selected-up :move-selected-down :delete-selected
    ;; Enter creates new block and moves focus - blur would exit edit mode
    :context-aware-enter
    ;; Paste with blank lines creates new blocks and moves focus to last block
    :paste-text
    ;; Arrow navigation changes editing-block-id - old block unmounts, blur fires
    :navigate-with-cursor-memory :navigate-to-adjacent
    ;; Delete/merge ops change editing-block-id to prev/next/merged block
    :delete :merge-with-prev :split-at-cursor})

(defn handle-intent
  "Single intent dispatcher - accepts {:type ...} intent maps only.

   This is the ONLY place intents are dispatched.
   Components call (on-intent {:type ...}) for all intents."
  [intent]
  (let [intent-map (if (keyword? intent) {:type intent} intent)]
    ;; Suppress blur-exit during structural ops to prevent focus loss during re-render
    (when (contains? structural-intents (:type intent-map))
      (vs/keep-edit-on-blur!))
    ;; Use shared runtime for the actual dispatch
    (executor/apply-intent! !db intent-map "DIRECT")))

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
  "Render outline tree by composing Block components.
   
   Also handles drag & drop at the container level for drops to first position."
  [{:keys [db root-id on-intent]}]
  (let [children (get-in db [:children-by-parent root-id] [])
        editing-block-id (vs/editing-block-id)
        focus-block-id (vs/focus-id)
        selection-set (vs/selection-nodes)
        folded-set (vs/folded)
        first-child-id (first children)
        ;; Check if dropping at top of outline
        drop-target (vs/drop-target)
        dropping-at-top? (and (= (:id drop-target) ::outline-top)
                              (= (:zone drop-target) :first))
        ;; Check if actively dragging
        dragging? (seq (vs/dragging-ids))]
    (if (empty? children)
      ;; Empty state: clickable placeholder to create first block
      [:div.outline.outline--empty
       {:style {:padding "40px 20px"
                :text-align "center"
                :color "#9ca3af"
                :cursor "text"
                :border "2px dashed #e5e7eb"
                :border-radius "8px"
                :margin "10px 0"}
        :on {:click (fn [_e]
                      ;; Create new block and enter edit mode
                      (let [new-id (str "block-" (random-uuid))]
                        (on-intent {:type :create-block-in-page
                                    :page-id root-id
                                    :block-id new-id})))
             ;; Allow drops on empty outline
             :dragover (fn [e]
                         (.preventDefault e)
                         (set! (.-dropEffect (.-dataTransfer e)) "move")
                         (vs/drag-over! ::outline-top :first))
             :drop (fn [e]
                     (.preventDefault e)
                     (let [dragging (vs/dragging-ids)]
                       (vs/drag-end!)
                       (when (seq dragging)
                         (on-intent {:type :move
                                     :selection (vec dragging)
                                     :parent root-id
                                     :anchor :first}))))}}
       [:p {:style {:margin 0}} "Click to start writing..."]]
      ;; Normal state: render block tree with dedicated top drop zone
      [:div.outline
       ;; Top drop zone - only shown during drag, intercepts drops above first block
       (when dragging?
         [:div.top-drop-zone
          {:style {:height (if dropping-at-top? "20px" "12px")
                   :margin-bottom "4px"
                   :border-radius "4px"
                   :transition "all 0.15s ease"
                   :background (if dropping-at-top?
                                 "rgba(59, 130, 246, 0.15)"
                                 "transparent")}
           :on {:dragover (fn [e]
                            (.preventDefault e)
                            (.stopPropagation e)
                            (set! (.-dropEffect (.-dataTransfer e)) "move")
                            (let [dragging (vs/dragging-ids)]
                              (when-not (contains? dragging first-child-id)
                                (vs/drag-over! ::outline-top :first))))
                :dragleave (fn [_e]
                             (when (= (:id (vs/drop-target)) ::outline-top)
                               (vs/drag-over! nil nil)))
                :drop (fn [e]
                        (.preventDefault e)
                        (.stopPropagation e)
                        (let [dragging (vs/dragging-ids)]
                          (vs/drag-end!)
                          (when (seq dragging)
                            (on-intent {:type :move
                                        :selection (vec dragging)
                                        :parent root-id
                                        :anchor :first}))))}}
          ;; Visual indicator line
          (when dropping-at-top?
            [:div {:style {:height "2px"
                           :background "#3b82f6"
                           :border-radius "1px"}}])])
       ;; Render blocks
       (map (fn [child-id]
              (block/Block {:db db
                            :block-id child-id
                            :depth 0
                            :is-focused (= focus-block-id child-id)
                            :is-selected (contains? selection-set child-id)
                            :is-editing (= editing-block-id child-id)
                            :is-folded (contains? folded-set child-id)
                            :on-intent on-intent}))
            children)])))

(defn PageTitle
  "Editable page title component.
   Matches journal-title styling. Click to edit, blur/Enter to save.
   Uses uncontrolled input pattern (browser owns value during edit)."
  [{:keys [page-id page-title on-intent]}]
  (let [editing? (vs/editing-page-title?)]
    (if editing?
      ;; Edit mode - uncontrolled input (browser owns value)
      [:div.page-title-header
       [:input.page-title-input
        {:type "text"
         :replicant/key (str page-id "-edit")
         :default-value page-title
         :replicant/on-mount (fn [{:replicant/keys [node]}]
                               (.focus node)
                               (.select node))
         :on {:blur (fn [e]
                      (let [new-title (str/trim (.-value (.-target e)))]
                        ;; Update DB first, THEN update view state
                        ;; This ensures re-render sees the new title
                        (when (and (not (str/blank? new-title))
                                   (not= new-title page-title))
                          (on-intent {:type :rename-page
                                      :page-id page-id
                                      :new-title new-title}))
                        (vs/set-editing-page-title! false)))
              :keydown (fn [e]
                         (case (.-key e)
                           "Enter" (do (.preventDefault e)
                                       (.blur (.-target e)))
                           "Escape" (do (.preventDefault e)
                                        ;; Reset value and exit without saving
                                        (set! (.-value (.-target e)) page-title)
                                        (vs/set-editing-page-title! false))
                           nil))}}]]
      ;; View mode - clickable h1
      [:div.page-title-header
       [:h1.page-title-display
        {:replicant/key (str page-id "-view")
         :on {:click (fn [_e]
                       (vs/set-editing-page-title! true))}}
        page-title]])))

(defn HotkeysReference []
  (let [kbd (fn [& key-names]
              (into [:span.kbd-group {:style {:display "inline-flex" :gap "2px" :align-items "center"}}]
                    (interpose [:span {:style {:color "#9ca3af" :font-size "10px"}} "+"]
                               (map (fn [k] [:kbd k]) key-names))))
        hotkey (fn [key-combo desc]
                 [:div.hotkey-item {:style {:display "flex" :align-items "center" :gap "8px" :margin "4px 0"}}
                  [:span {:style {:min-width "90px"}} key-combo]
                  [:span {:style {:color "#6b7280"}} desc]])]
    [:div.hotkeys-footer
     {:style {:margin-top "30px"
              :padding "24px"
              :background "linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%)"
              :border-radius "8px"
              :border "1px solid #e2e8f0"}}
     [:h4 {:style {:margin "0 0 16px 0" :font-size "14px" :font-weight "600" :color "#374151"}}
      "⌨️ Keyboard Shortcuts"]
     [:div {:style {:display "grid"
                    :grid-template-columns "repeat(auto-fit, minmax(220px, 1fr))"
                    :gap "20px"}}
      ;; Navigation
      [:div
       [:h5 {:style {:margin "0 0 8px 0" :font-size "11px" :text-transform "uppercase"
                     :letter-spacing "0.05em" :color "#9ca3af"}} "Navigation"]
       (hotkey (kbd "↑") "Previous block")
       (hotkey (kbd "↓") "Next block")
       (hotkey (kbd "Shift" "↑") "Extend selection up")
       (hotkey (kbd "Shift" "↓") "Extend selection down")
       (hotkey (kbd "Esc") "Exit edit mode")
       (hotkey (kbd "Enter") "Edit selected block")]
      ;; Editing
      [:div
       [:h5 {:style {:margin "0 0 8px 0" :font-size "11px" :text-transform "uppercase"
                     :letter-spacing "0.05em" :color "#9ca3af"}} "Editing"]
       (hotkey (kbd "Enter") "Split / new block")
       (hotkey (kbd "Shift" "Enter") "New line in block")
       (hotkey (kbd "⌫") "Delete / merge")
       (hotkey (kbd "Tab") "Indent")
       (hotkey (kbd "Shift" "Tab") "Outdent")
       (hotkey (kbd "⌘" "Enter") "Toggle checkbox")]
      ;; Structure
      [:div
       [:h5 {:style {:margin "0 0 8px 0" :font-size "11px" :text-transform "uppercase"
                     :letter-spacing "0.05em" :color "#9ca3af"}} "Structure"]
       (hotkey (kbd "⌘" "Shift" "↑") "Move block up")
       (hotkey (kbd "⌘" "Shift" "↓") "Move block down")
       (hotkey (kbd "⌘" ";") "Toggle fold")
       (hotkey (kbd "⌘" "↑") "Collapse")
       (hotkey (kbd "⌘" "↓") "Expand all")]
      ;; Zoom & Undo
      [:div
       [:h5 {:style {:margin "0 0 8px 0" :font-size "11px" :text-transform "uppercase"
                     :letter-spacing "0.05em" :color "#9ca3af"}} "Zoom & Undo"]
       (hotkey (kbd "⌘" ".") "Zoom in")
       (hotkey (kbd "⌘" ",") "Zoom out")
       (hotkey (kbd "⌘" "Z") "Undo")
       (hotkey (kbd "⌘" "Shift" "Z") "Redo")]
      ;; UI
      [:div
       [:h5 {:style {:margin "0 0 8px 0" :font-size "11px" :text-transform "uppercase"
                     :letter-spacing "0.05em" :color "#9ca3af"}} "UI"]
       (hotkey (kbd "⌘" "\\") "Toggle sidebar")
       (hotkey (kbd "⌘" "P") "Toggle this panel")
       (hotkey (kbd "⌘" "Shift" "E") "Toggle reading mode")]]]))

(defn- FloatingControls
  "Bottom-right floating buttons: reading mode + hotkey panel toggle."
  [{:keys [on-intent reading-mode? hotkeys-visible?]}]
  [:div.floating-controls
   [:button.floating-btn
    {:type "button"
     :title "Reading mode (⌘⇧E)"
     :aria-label "Toggle reading mode"
     :aria-pressed (boolean reading-mode?)
     :class (when reading-mode? "is-active")
     :on {:click (fn [_] (on-intent {:type :toggle-reading-mode}))}}
    "Aa"]
   [:button.floating-btn
    {:type "button"
     :title "Keyboard shortcuts (⌘P)"
     :aria-label "Toggle keyboard shortcuts"
     :aria-pressed (boolean hotkeys-visible?)
     :class (when hotkeys-visible? "is-active")
     :on {:click (fn [_] (on-intent {:type :toggle-hotkeys}))}}
    "?"]])

(defn App
  "Main app - pure composition, no business logic."
  []
  (let [db @!db
        storage-status @!storage-status
        checking? (:checking? storage-status)
        embed? (embed-mode?)
        current-page-id (vs/current-page)
        page-title (when current-page-id (q/page-title db current-page-id))
        sidebar-visible? (vs/sidebar-visible?)
        hotkeys-visible? (vs/hotkeys-visible?)
        reading-mode? (vs/reading-mode?)
        journals-view? (vs/journals-view?)
        quick-switcher-visible? (vs/quick-switcher-visible?)]
    [:div {:class (str "app"
                       (when embed? " app--embed")
                       (when reading-mode? " reading-mode"))}
     ;; Sidebar for page navigation (toggleable via Cmd+\)
     ;; Always show sidebar - it has the folder picker
     (when (and sidebar-visible? (not embed?) (not reading-mode?))
       (sidebar/Sidebar {:db db
                         :on-intent handle-intent
                         :on-pick-folder pick-folder!
                         :on-clear-folder clear-folder!
                         :storage-status storage-status}))

     ;; Main wrapper - flexbox centering for content
     [:div {:class (str "main-wrapper" (when embed? " main-wrapper--embed"))}
      ;; Main content area - only render after storage check completes
      (when-not checking?
        [:main {:class (str "main-content" (when embed? " main-content--embed"))
                :on {:click (fn [_e]
                              ;; Background click to clear selection (Logseq parity)
                              (when-not (vs/editing-block-id)
                                (handle-intent {:type :selection :mode :clear})))}} 

         ;; Mock-text for cursor detection
         (MockText)

         ;; Header with navigation

;; Main content area - journals view, current page, or empty state
         (cond
           ;; Journals view - all journals stacked
           journals-view?
           (journals/JournalsView {:db db :on-intent handle-intent})

           ;; Single page view
           current-page-id
           [:div {:class (when embed? "embed-shell")}
            ;; Editable page title (click to rename)
            (when-not embed?
              (PageTitle {:page-id current-page-id
                          :page-title page-title
                          :on-intent handle-intent}))

            ;; Main outline for current page only
            (Outline {:db db
                      :root-id current-page-id
                      :on-intent handle-intent})

            ;; Backlinks panel - shows "Linked References" from other pages
            (when-not embed?
              (backlinks/BacklinksPanel {:db db
                                         :page-title page-title
                                         :on-intent handle-intent}))]

           ;; All Pages view (no page selected)
           :else
           (all-pages/AllPagesView {:db db :on-intent handle-intent}))

         ;; Dev tools (Simplified: Event → Human-Spec → DB Diff)
         ;; Auto-show when ?devtools query param present
         (when (devtools-enabled?)
           (devtools/DevToolsPanel {:db db}))

         ;; Hotkeys reference (toggleable via Cmd+?)
         (when hotkeys-visible?
           (HotkeysReference))])]

     ;; Quick Switcher overlay (Cmd+K) - rendered outside main content for proper modal behavior
     (when (and quick-switcher-visible? (not embed?))
       (quick-switcher/QuickSwitcher {:db db :on-intent handle-intent}))

     ;; Toast notification (uses Popover API for top-layer rendering)
     (when-not embed?
       (notification/Notification))

     ;; Lightbox overlay for fullscreen image viewing
     (when-not embed?
       (lightbox/Lightbox))

     ;; Floating controls (bottom-right): reading-mode + hotkeys toggles
     (when-not embed?
       (FloatingControls {:on-intent handle-intent
                          :reading-mode? reading-mode?
                          :hotkeys-visible? hotkeys-visible?}))]))

;; ── Main ──────────────────────────────────────────────────────────────────────

(defn render! []
  (d/render (js/document.getElementById "root")
            (if (specs-mode?)
              (spec-viewer/SpecViewer)
              (App))))

;; Batched render using requestAnimationFrame to prevent nested render warnings.
;; When multiple state changes (DB + session) happen in the same frame,
;; this coalesces them into a single render call.
(defonce ^:private render-scheduled? (atom false))

(defn- process-auto-trash-queue!
  "Process queued pages for auto-trash check.
   Called after render to avoid nested dispatch.

   Skipped under `?test=true` — the 100 ms queue otherwise silently
   trashes empty fixture pages created by `:create-page` in tests and
   takes focus with it through `handle-delete-page`'s session update,
   making any multi-page e2e scenario flake. In tests, fixtures are
   ephemeral by definition; auto-trash is a prod-side UX feature."
  []
  (when-not (test-mode?)
    (doseq [page-id (vs/take-auto-trash-queue!)]
      (executor/apply-intent! !db
                              {:type :auto-trash-empty-page
                               :page-id page-id}
                              "AUTO-TRASH"))))

(defn request-render!
  "Request a render on the next animation frame.
   Multiple calls in the same frame are coalesced into one render.
   Also processes auto-trash queue after render completes."
  []
  (when-not @render-scheduled?
    (reset! render-scheduled? true)
    (js/requestAnimationFrame
     (fn []
       (reset! render-scheduled? false)
       (render!)
       ;; Process auto-trash queue after render completes
       (js/setTimeout process-auto-trash-queue! 100)))))

;; ── Test Helpers ──────────────────────────────────────────────────────────────

(defn reset-to-empty-db!
  "Reset database to empty state for E2E tests with one empty block.
   Exposed on window.TEST_HELPERS for Playwright."
  []
  ;; Reset DB (document only, no session data). The fixture is the new
  ;; baseline, so it becomes the log's :root-db (not an undoable action).
  (let [initial (:db (tx/interpret
                      (db/empty-db)
                      [{:op :create-node :id "test-page" :type :page :props {:title "Test Page"}}
                       {:op :place :id "test-page" :under :doc :at :last}
                       {:op :create-node :id "test-block-1" :type :block :props {:text ""}}
                       {:op :place :id "test-block-1" :under "test-page" :at :last}]))]
    (reset! !db initial)
    (slog/reset-with-db! initial))
  ;; Reset session and set current page
  (vs/reset-view-state!)
  (vs/set-journals-view! false) ; Disable journals view so test-page is visible
  (vs/set-current-page! "test-page")
  ;; Clear storage checking state (no folder check needed in test mode)
  (swap! !storage-status assoc :checking? false))

(defn- normalize-view-state-updates
  "Convert JS/EDN fixture payload into view-state updates with proper sets."
  [session]
  (cond-> session
    (sequential? (get-in session [:selection :nodes]))
    (update-in [:selection :nodes] set)

    (sequential? (get-in session [:ui :folded]))
    (update-in [:ui :folded] set)))

(defn load-fixture!
  "Load a fixture payload into the editor for embedded demos/tests.

   Payload shape:
   {:ops [...]
    :session {:selection {:nodes [...] :focus ... :anchor ...}
              :ui {:current-page ... :editing-block-id ... :cursor-position ...
                   :folded [...] :zoom-root ...}}}"
  [payload-js]
  (let [payload (js->clj payload-js :keywordize-keys true)
        ops (mapv (fn [op]
                    (cond-> op
                      (:op op) (update :op keyword)
                      (:type op) (update :type keyword)
                      (:at op) (update :at keyword)
                      (and (:under op) (string? (:under op))
                           (#{"doc" ":doc" "trash" ":trash"} (:under op)))
                      (update :under #(keyword (str/replace % #"^:" "")))))
                  (:ops payload))
        session (normalize-view-state-updates (or (:session payload) {}))
        result (tx/interpret (db/empty-db) ops)
        loaded (:db result)]
    (reset! !db loaded)
    (slog/reset-with-db! loaded)
    (vs/reset-view-state!)
    (vs/merge-view-state-updates! session)
    (swap! !storage-status assoc :checking? false)))

(defn main []
  (when ^boolean goog.DEBUG
    (js/console.log "Blocks UI starting...")
    ;; Log uncited intents once (grouped) instead of on each registration
    (intent/log-uncited-intents!))

;; Reset to empty DB for E2E tests (handles hot-reload where defonce doesn't re-run)
  (when (test-mode?)
    (reset-to-empty-db!))

  ;; Expose test helpers for E2E tests
  (set! (.-TEST_HELPERS js/window)
        #js {:resetToEmptyDb reset-to-empty-db!
             ;; First-class affordance so journals.spec doesn't need to
             ;; click the sidebar nav (flaky under Replicant re-render
             ;; timing). `reset-to-empty-db!` sets journals-view? false;
             ;; tests that need journals view must explicitly enter it.
             :openJournalsView (fn []
                                 (handle-intent {:type :open-journals-view}))
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
             :getSession (fn [] (clj->js (vs/get-view-state)))
             :showLightbox (fn [src alt]
                             (lightbox/show! {:src src :alt alt}))
             :hideLightbox (fn []
                             (lightbox/hide!))
             :clearFolder (fn []
                            (clear-folder!))
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
                           (reset! !db (:db result))))
             :loadFixture load-fixture!

             ;; ── Debug Helpers (for E2E test diagnostics) ────────────────────────
             ;; Debug an intent - check if it would be allowed and why
             :debugIntent (fn [intent-js]
                            (let [raw (js->clj intent-js :keywordize-keys true)
                                  intent (cond-> raw
                                           (:type raw) (update :type keyword)
                                           (:mode raw) (update :mode keyword))
                                  current-session (vs/get-view-state)
                                  state (sm/current-state current-session)
                                  allowed? (sm/intent-allowed? current-session intent)
                                  requirements (sm/get-intent-requirements (:type intent))]
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
                         (let [current-session (vs/get-view-state)]
                           #js {:state (name (sm/current-state current-session))
                                :editingBlockId (get-in current-session [:ui :editing-block-id])
                                :selectedIds (clj->js (vec (get-in current-session [:selection :nodes] #{})))
                                :focusId (get-in current-session [:selection :focus])
                                :bufferBlockId (get-in current-session [:buffer :block-id])
                                :bufferDirty (get-in current-session [:buffer :dirty?])}))

	             ;; Copy full debug state to clipboard for bug reports
	             :copyDebugState (fn []
	                               (let [current-session (vs/get-view-state)
	                                     db-snapshot @!db
                                     debug-data {:timestamp (.toISOString (js/Date.))
                                                 :state (sm/current-state current-session)
                                                 :session current-session
                                                 :db {:nodes (get db-snapshot :nodes)
                                                      :children-by-parent (get db-snapshot :children-by-parent)
                                                      :roots (get db-snapshot :roots)}
                                                 :dom {:activeElement (when-let [el (.-activeElement js/document)]
                                                                        {:tagName (.-tagName el)
                                                                         :id (.-id el)
                                                                         :className (.-className el)
                                                                         :contentEditable (.-contentEditable el)})
                                                       :selection (when-let [sel (.getSelection js/window)]
                                                                    {:type (.-type sel)
                                                                     :anchorOffset (.-anchorOffset sel)
                                                                     :focusOffset (.-focusOffset sel)
                                                                     :isCollapsed (.-isCollapsed sel)})}}
                                     json-str (js/JSON.stringify (clj->js debug-data) nil 2)]
                                 (-> (js/navigator.clipboard.writeText json-str)
                                     (.then #(js/console.log "✅ Debug state copied to clipboard"))
                                     (.catch #(js/console.error "❌ Failed to copy:" %)))
	                                 (js/console.log "Debug state:" debug-data)
	                                 json-str))})

  (when (and (embed-mode?) (seq (.-name js/window)))
    (try
      (load-fixture! (.parse js/JSON (.-name js/window)))
      (catch :default err
        (js/console.error "Failed to load embed fixture from window.name" err))))

  ;; Phase 2: Expose session for debugging
  (set! (.-SESSION js/window) vs/!view-state)

  ;; Initialize plugin/bootstrap surfaces explicitly.
  (plugins/init!)

  ;; Initialize keyboard bindings (explicit, not side-effect driven)
  (bindings/reload!)

  ;; Initialize IME composition tracking for CJK/emoji input safety
  ;; Tracks compositionstart/compositionend at document level
  (cursor-bounds/setup-composition-tracking!)

  ;; Load persisted favorites and recents from localStorage
  (vs/load-persisted-state!)

  ;; Cleanup old trash (30+ days) and scan for empty pages on startup
  ;; Deferred to allow DB to load first
  (js/setTimeout
   (fn []
     (when-not (test-mode?)
       ;; First, mark pages in trash > 30 days as tombstones
       (executor/apply-intent! !db {:type :cleanup-old-trash} "STARTUP")
       ;; Then, garbage collect tombstoned nodes. gc-tombstones bypasses the
       ;; transaction pipeline (it's housekeeping, not a user action), so we
       ;; must re-anchor the log to match or undo would resurrect the GC'd
       ;; nodes. Accepting: GC drops prior undo history.
       (swap! !db api/gc-tombstones)
       (slog/reset-with-db! @!db)
       ;; Finally, auto-trash any empty pages (except today's journal)
       (executor/apply-intent! !db {:type :scan-empty-pages} "STARTUP")))
   2000)

;; Note: Current page is set by load-from-folder! after storage check completes

  ;; Enable lifecycle hooks and function-based DOM handlers.
  ;; CRITICAL: Lifecycle hooks must still fire for cursor placement
  (d/set-dispatch!
   (fn [event-data handler-data]
     (dispatch-bridge/dispatch-handler-data! event-data handler-data)))

  ;; Set up global keyboard listener (Cmd+Z, etc)
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (global-keyboard/handle-keydown !db handle-intent e)))

  ;; Set up auto-render on state changes (DB, session, and storage status)
  ;; Uses request-render! to batch multiple changes into single render (prevents nested render warnings)
  (add-watch !db :render (fn [_ _ _ _] (request-render!)))
  (add-watch vs/!view-state :render (fn [_ _ _ _] (request-render!)))
  (add-watch !storage-status :render (fn [_ _ _ _] (request-render!)))

;; Initialize Dataspex for state inspection
  ;; NOTE: track-changes disabled to prevent memory accumulation during heavy use
  ;; Only inspect DB and view-state (primary debugging targets)
  ;; Logs are available via tooling/get-log and tooling/get-clipboard-log from REPL
  ;; but not in Dataspex to avoid per-intent overhead
  (dataspex/inspect "App DB" !db)
  (dataspex/inspect "View State" vs/!view-state)

  ;; Install queryable DEBUG API for AI tools and E2E tests
  (debug-api/install! !db)

  ;; Apply text selection effects from formatting operations
  ;; Watch session for pending-selection instead of DB
  (add-watch vs/!view-state :text-selection-effects
             (fn [_ _ _ new-session]
               (when-let [{:keys [block-id start end]}
                          (get-in new-session [:ui :pending-selection])]
                 (js/requestAnimationFrame
                  (fn []
                    (when-let [editable-el (.querySelector js/document
                                                           (str "[data-block-id='" block-id "'] .block-content"))]
                      (try
                        ;; CRITICAL: Update DOM text from DB BEFORE setting selection
                        ;; This ensures formatting changes (e.g., **bold**) are visible in contenteditable
                        ;; Without this, blur handler would commit stale DOM text back to DB
                        (let [db @!db
                              db-text (get-in db [:nodes block-id :props :text] "")]
                          (when (not= (.-textContent editable-el) db-text)
                            (set! (.-textContent editable-el) db-text)))
                        ;; Use make-range for correct multi-node DOM handling
                        (let [range (text-sel/make-range editable-el start end)]
                          (text-sel/set-current-range! range))
                        (catch js/Error e
                          (js/console.error "Text selection failed:" e))))
                    ;; Clear pending selection after applying
                    (vs/clear-pending-selection!))))))

  (render!))
