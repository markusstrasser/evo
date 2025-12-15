(ns shell.editor
  "Blocks UI demo - composition layer only.

   Demonstrates proper architecture:
   - Plugins provide getters and extend intent multimethods
   - Components use getters and dispatch intents
   - App just composes components and routes intents"
  (:require [clojure.string :as str]
            [replicant.dom :as d]
            [kernel.db :as db]
            [kernel.query :as q]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [components.block :as block]
            [components.sidebar :as sidebar]
            [components.devtools :as devtools]
            [components.backlinks :as backlinks]
            [dataspex.core :as dataspex]
            [shell.nexus :as nexus]
            [shell.executor :as executor]
            [shell.storage :as storage]
            [shell.e2e-scenarios]
            [shell.view-state :as vs]
            [dev.tooling :as tooling]
            [debug-api]
            ;; Load all plugins to register intents
            [plugins.selection]
            [plugins.editing]
            [plugins.clipboard]
            [plugins.navigation]
            [plugins.structural]
            [plugins.folding]
            [plugins.context-editing]
            [plugins.text-formatting]
            [plugins.autocomplete]
            ;; Phase 3: [plugins.buffer] removed - buffer now purely in session
            [plugins.pages]
            [plugins.backlinks-index]
            [keymap.core :as keymap]
            [keymap.bindings :as bindings]
            [kernel.state-machine :as sm]
            [kernel.intent :as intent]
            [components.spec-viewer :as spec-viewer]
            [components.quick-switcher :as quick-switcher]
            [components.notification :as notification]
            [components.journals :as journals]
            [components.all-pages :as all-pages]
            [utils.journal :as journal]
            [utils.cursor-boundaries :as cursor-bounds]))

;; ── State atom ────────────────────────────────────────────────────────────────

(defn- test-mode?
  "Check if running in E2E test mode"
  []
  (let [search (.-search js/location)
        has-param (and search (>= (.indexOf search "test=true") 0))]
    (boolean has-param)))

(defn- devtools-enabled?
  "Check if devtools UI should be shown (via ?devtools query param)"
  []
  (let [search (.-search js/location)]
    (boolean (and search (>= (.indexOf search "devtools") 0)))))

(defn- specs-mode?
  "Check if spec viewer should be shown (via ?specs query param)"
  []
  (let [search (.-search js/location)]
    (boolean (and search (>= (.indexOf search "specs") 0)))))

;; Initial DB - starts with demo content, replaced when folder is loaded
(defonce !db
  (atom
   ;; Always start with empty DB - demo data loaded only if no folder configured
   (-> (db/empty-db) (H/record))))

;; Storage status atom for UI feedback
(defonce !storage-status
  (atom {:folder-name nil
         :loading? false
         ;; Start in checking state - true until we know if folder exists
         :checking? true}))

(defn load-from-folder!
  "Load pages from the currently selected folder into DB.
   If folder is empty, starts with empty DB.
   Always navigates to today's daily journal (creating if needed)."
  []
  (swap! !storage-status assoc :loading? true)
  (-> (storage/load-all-pages)
      (.then (fn [ops]
               (if (seq ops)
                 ;; Folder has pages - load them
                 (do
                   (js/console.log "📂 Loading" (count ops) "ops from folder...")
                   (reset! !db (-> (db/empty-db)
                                   (tx/interpret ops)
                                   :db
                                   (H/record))))
                 ;; Empty folder - start with empty DB
                 (do
                   (js/console.log "📂 Empty folder, starting fresh")
                   (reset! !db (-> (db/empty-db) (H/record)))))
               ;; Always navigate to today's journal (creates if needed)
               (js/console.log "📅 Opening today's journal:" (journal/today-title))
               (executor/apply-intent! !db
                                       {:type :go-to-journal
                                        :journal-title (journal/today-title)}
                                       "STARTUP")
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
  (storage/clear-folder!)
  (swap! !storage-status assoc :folder-name nil)
  ;; Reset to empty DB (no demo data - user must pick folder or start fresh)
  (reset! !db (-> (db/empty-db) (H/record)))
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

;; Auto-save to folder on DB changes (debounced)
(defonce ^:private save-timeout (atom nil))

(defn- schedule-save!
  "Debounced save - waits 500ms after last change before saving."
  [db-val]
  (when-let [t @save-timeout]
    (js/clearTimeout t))
  (reset! save-timeout
          (js/setTimeout
           (fn []
             (when (and (not (test-mode?)) (storage/has-folder?))
               (storage/save-db! db-val)))
           500)))

(defonce _db-watcher
  (add-watch !db :auto-save
             (fn [_ _ _ new-val]
               (schedule-save! new-val))))

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

;; ── Global keyboard shortcuts (Keymap Resolver) ───────────────────────────────

(defn handle-global-keydown
  "Global keyboard shortcuts via central keymap resolver.

   Single source of truth: keymap/bindings.cljc registers all bindings.
   This function just resolves key event → intent type → dispatch.

   NOTE: Arrow key navigation at block boundaries is handled by the Block component
   (see components/block.cljs handle-arrow-up/down) using cursor row detection,
   NOT here. The block component dispatches :navigate-with-cursor-memory intents."
  [e]
  ;; Skip if another handler already handled this event (e.g., Block component)
  (when-not (.-defaultPrevented e)
    (let [event (keymap/parse-dom-event e)
          db @!db
          current-session (vs/get-view-state)
          key (.-key e)
          mod? (or (.-metaKey e) (.-ctrlKey e))
          shift? (.-shiftKey e)
          focus-id (vs/focus-id)
          editing? (vs/editing-block-id)
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

      ;; FR-Idle-02: Shift+Arrow in idle state selects first/last block (Logseq parity)
      ;; Same behavior as plain Arrow - starts block selection
        (and idle?
             shift?
             (contains? #{"ArrowUp" "ArrowDown"} key))
        (let [visible-blocks (q/visible-blocks db current-session)
              target-id (if (= key "ArrowUp")
                          (last visible-blocks)
                          (first visible-blocks))]
          (when target-id
            (.preventDefault e)
            (handle-intent {:type :selection :mode :replace :ids target-id})))

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

      ;; CLIPBOARD: Cmd+V paste in non-editing mode (async clipboard read)
      ;; When a block is focused but not being edited, paste replaces block content
      ;; or creates new blocks from clipboard markdown
        (and mod?
             (= key "v")
             (not shift?)
             focus-id
             (not editing?))
        (do
          (.preventDefault e)
          ;; Async clipboard read - dispatch intent when data arrives
          (-> (js/navigator.clipboard.readText)
              (.then (fn [text]
                       (when (and text (pos? (count text)))
                         ;; Enter edit mode and paste at cursor 0
                         ;; This triggers markdown parsing via :paste-text intent
                         (handle-intent {:type :paste-text
                                         :block-id focus-id
                                         :cursor-pos 0
                                         :selection-end (count (get-in db [:nodes focus-id :props :text] ""))
                                         :pasted-text text}))))
              (.catch (fn [err]
                        (js/console.error "Clipboard read failed:" err)))))

      ;; LOGSEQ_SPEC §7.2: Cmd+A cycle in editing mode
      ;; First press → browser select-all (let event through)
      ;; Second press (all text selected) → exit edit, select block
        (and editing?
             mod?
             (= key "a")
             (not shift?)
             editable-el)
        (try
          (let [sel (.getSelection js/window)
                text-length (count (.-textContent editable-el))
              ;; Check if all text is already selected
                all-selected? (and sel
                                   (pos? (.-rangeCount sel))
                                   (let [sel-text (str (.toString sel))]
                                     (= (count sel-text) text-length)))]
            (if all-selected?
            ;; All text selected → exit edit and select block (step 2 of cycle)
              (do (.preventDefault e)
                  (handle-intent {:type :select-all-cycle
                                  :from-editing? true
                                  :block-id editing?}))
            ;; Not all selected → let browser handle select-all (step 1)
              nil))
          (catch js/Error _
          ;; On error, let browser handle
            nil))

      ;; Keymap-resolved intent
        intent-type
        (do (.preventDefault e)
            (cond
            ;; Undo/Redo - modify DB directly, not via operations
            ;; Also restore session state (cursor, selection) for proper context
              (= intent-type :undo)
              (when-let [{:keys [db session]} (H/undo @!db (vs/get-view-state))]
              ;; Restore session BEFORE db to ensure cursor is set before re-render
                (when session
                  (vs/merge-view-state-updates! session))
                (reset! !db db)
                (executor/assert-derived-fresh! db "after undo"))

              (= intent-type :redo)
              (when-let [{:keys [db session]} (H/redo @!db (vs/get-view-state))]
              ;; Restore session BEFORE db to ensure cursor is set before re-render
                (when session
                  (vs/merge-view-state-updates! session))
                (reset! !db db)
                (executor/assert-derived-fresh! db "after redo"))

            ;; All other intents - go through normal intent dispatch
              :else
              (let [;; Inject focused block-id for fold/zoom intents
                    ;; Use editing block if no selection focus (supports fold while editing)
                    effective-block-id (or focus-id editing?)
                    intent-with-focus (if (and (map? intent-type)
                                               (#{:toggle-fold :collapse :expand-all :zoom-in} (:type intent-type))
                                               effective-block-id)
                                        (assoc intent-type :block-id effective-block-id)
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
                                      :else intent-with-id)
                  ;; Structural operations during editing require text commit first
                  ;; (to prevent losing uncommitted DOM text changes)
                    structural-intent? (contains? #{:indent-selected :outdent-selected
                                                    :move-selected-up :move-selected-down}
                                                  (if (map? enriched-intent)
                                                    (:type enriched-intent)
                                                    enriched-intent))]
                (when enriched-intent ;; Only dispatch if enrichment succeeded
                ;; Commit text AND preserve cursor before structural operations while editing
                  (when (and editing? structural-intent?)
                  ;; Suppress blur FIRST to prevent focus loss during re-renders
                    (vs/keep-edit-on-blur!)
                  ;; Capture cursor position BEFORE any changes (will be saved after text commit)
                    (let [sel (.getSelection js/window)
                          saved-cursor-pos (when (and sel editable-el) (.-anchorOffset sel))
                          buffer-text (vs/buffer-text editing?)
                          dom-text (when editable-el (.-textContent editable-el))
                          final-text (or buffer-text dom-text)]
                    ;; Commit text first
                      (when final-text
                        (handle-intent {:type :update-content
                                        :block-id editing?
                                        :text final-text}))
                    ;; Save cursor position AFTER text commit (on-render may have cleared it)
                    ;; on-mount will read this and restore cursor position after block remounts
                      (when saved-cursor-pos
                        (vs/set-cursor-position! saved-cursor-pos))))
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
          (handle-intent {:type :enter-edit-with-char :block-id focus-id :char key}))))))

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
       (hotkey (kbd "⌘" "B") "Toggle sidebar")
       (hotkey (kbd "⌘" "?") "Toggle this panel")]]]))

(defn App
  "Main app - pure composition, no business logic."
  []
  (let [db @!db
        storage-status @!storage-status
        checking? (:checking? storage-status)
        current-page-id (vs/current-page)
        page-title (when current-page-id (q/page-title db current-page-id))
        sidebar-visible? (vs/sidebar-visible?)
        hotkeys-visible? (vs/hotkeys-visible?)
        journals-view? (vs/journals-view?)
        quick-switcher-visible? (vs/quick-switcher-visible?)
        ;; Navigation history state
        can-go-back? (vs/can-go-back?)
        can-go-forward? (vs/can-go-forward?)]
    [:div.app
     {:style {:display "flex"
              :min-height "100vh"}}

     ;; Sidebar for page navigation (toggleable via Cmd+B)
     ;; Always show sidebar - it has the folder picker
     (when sidebar-visible?
       (sidebar/Sidebar {:db db
                         :on-intent handle-intent
                         :on-pick-folder pick-folder!
                         :on-clear-folder clear-folder!
                         :storage-status storage-status}))

     ;; Main content area - only render after storage check completes
     (when-not checking?
       [:div.main-content
        {:style {:flex "1"
                 :margin-left (if sidebar-visible? "260px" "20px") ; Offset for fixed sidebar (240px + 20px gap)
                 :font-family "system-ui, -apple-system, sans-serif"
                 :padding "20px"
                 :max-width "800px"}
         ;; Background click to clear selection (Logseq parity)
         ;; Blocks call stopPropagation, so this only fires for empty background clicks
         :on {:click (fn [_e]
                       ;; Only clear if not editing and clicking empty background
                       (when-not (vs/editing-block-id)
                         (handle-intent {:type :selection :mode :clear})))}}

        ;; Mock-text for cursor detection
        (MockText)

        ;; Header with navigation
        [:div.page-header
         {:style {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :margin-bottom "10px"}}
         ;; Navigation arrows (subtle, always visible when history exists)
         [:div.nav-arrows
          {:style {:display "flex"
                   :gap "2px"}}
          [:button.nav-arrow
           {:style {:background "none"
                    :border "none"
                    :padding "4px 6px"
                    :cursor (if can-go-back? "pointer" "default")
                    :color (if can-go-back? "#6b7280" "#d1d5db")
                    :font-size "14px"
                    :border-radius "4px"
                    :transition "all 0.15s ease"}
            :title "Go back (Cmd+[)"
            :disabled (not can-go-back?)
            :on {:click (fn [e]
                          (.preventDefault e)
                          (when can-go-back?
                            (handle-intent {:type :navigate-back})))}}
           "←"]
          [:button.nav-arrow
           {:style {:background "none"
                    :border "none"
                    :padding "4px 6px"
                    :cursor (if can-go-forward? "pointer" "default")
                    :color (if can-go-forward? "#6b7280" "#d1d5db")
                    :font-size "14px"
                    :border-radius "4px"
                    :transition "all 0.15s ease"}
            :title "Go forward (Cmd+])"
            :disabled (not can-go-forward?)
            :on {:click (fn [e]
                          (.preventDefault e)
                          (when can-go-forward?
                            (handle-intent {:type :navigate-forward})))}}
           "→"]]
         [:h2 {:style {:margin "0"}} "Blocks UI"]]

        [:p {:style {:color "#666" :margin-top "5px"}}
         "Page refs " [:code "[[Page]]"]]

        ;; Main content area - journals view, current page, or empty state
        (cond
          ;; Journals view - all journals stacked
          journals-view?
          (journals/JournalsView {:db db :on-intent handle-intent})

          ;; Single page view
          current-page-id
          [:div
           [:h3 {:style {:margin-top "20px"
                         :margin-bottom "10px"
                         :color "rgb(29, 78, 216)"}}
            "📄 " page-title]

           ;; Main outline for current page only
           (Outline {:db db
                     :root-id current-page-id
                     :on-intent handle-intent})

           ;; Backlinks panel - shows "Linked References" from other pages
           (backlinks/BacklinksPanel {:db db
                                      :page-title page-title
                                      :on-intent handle-intent})]

;; All Pages view (no page selected)
          :else
          (all-pages/AllPagesView {:db db :on-intent handle-intent}))

        ;; Dev tools (Simplified: Event → Human-Spec → DB Diff)
        ;; Auto-show when ?devtools query param present
        (when (devtools-enabled?)
          (devtools/DevToolsPanel {:db db}))

        ;; Hotkeys reference (toggleable via Cmd+?)
        (when hotkeys-visible?
          (HotkeysReference))])

     ;; Quick Switcher overlay (Cmd+K) - rendered outside main content for proper modal behavior
     (when quick-switcher-visible?
       (quick-switcher/QuickSwitcher {:db db :on-intent handle-intent}))

     ;; Toast notification (uses Popover API for top-layer rendering)
     (notification/Notification)]))

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
  (reset! !db (-> (db/empty-db)
                  (tx/interpret [{:op :create-node :id "test-page" :type :page :props {:title "Test Page"}}
                                 {:op :place :id "test-page" :under :doc :at :last}
                                 {:op :create-node :id "test-block-1" :type :block :props {:text ""}}
                                 {:op :place :id "test-block-1" :under "test-page" :at :last}])
                  :db
                  (H/record)))
  ;; Reset session and set current page
  (vs/reset-view-state!)
  (vs/set-current-page! "test-page")
  ;; Clear storage checking state (no folder check needed in test mode)
  (swap! !storage-status assoc :checking? false))

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

  ;; Phase 2: Expose session for debugging
  (set! (.-SESSION js/window) vs/!view-state)

  ;; Initialize keyboard bindings (explicit, not side-effect)
  (bindings/reload!)

  ;; Initialize Nexus action pipeline
  (nexus/init!)

  ;; Initialize IME composition tracking for CJK/emoji input safety
  ;; Tracks compositionstart/compositionend at document level
  (cursor-bounds/setup-composition-tracking!)

  ;; Load persisted favorites and recents from localStorage
  (vs/load-persisted-state!)

;; Note: Current page is set by load-from-folder! after storage check completes

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
                    (vs/clear-pending-selection!))))))

  (render!))
