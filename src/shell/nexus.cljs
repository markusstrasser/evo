(ns shell.nexus
  "Nexus action pipeline for Evo.

   All DOM events → Nexus actions → Effects → Kernel intents.

   Architecture:
   - Actions: Pure reducers that return effect lists
   - Effects: Side-effect wrappers (dispatch intents, log, etc.)
   - Placeholders: Late-bound DOM data (selection offsets, caret position)

   Usage:
     (dispatch! [:editing/navigate-up {:block-id \"abc\"}])

   Dev observability:
     window.__nexusLog = [{:action ... :intent ... :timestamp ...}]"
  (:require [nexus.registry :as nxr]
            [kernel.api :as api]
            [kernel.db :as db]
            [shell.session :as session]
            [dev.tooling :as dev]
            [clojure.string :as str]))

;; ── Effects ───────────────────────────────────────────────────────────────────

(defn- assert-derived-fresh!
  "DEBUG: Assert that derived indexes are consistent after DB reset."
  [db-val label]
  (when ^boolean goog.DEBUG
    (when-let [inconsistency (db/check-parent-of-consistency db-val)]
      (js/console.error "🚨🚨🚨 DERIVED INDEX CORRUPTION DETECTED 🚨🚨🚨"
                        "\nLabel:" label
                        "\nInconsistency:" (pr-str inconsistency)
                        "\nDB hash:" (hash db-val))
      (js/console.trace "Stack trace for corruption detection"))))

(defn dispatch-intent
  "Effect that dispatches kernel intents.

   Gets current session, passes to api/dispatch, applies session-updates."
  [_ !db intent-map]
  (let [current-session (session/get-session)
        db-before @!db
        ;; DEBUG: Log ALL intent dispatches through Nexus
        _ (js/console.log "🔷 NEXUS dispatch:" (pr-str (:type intent-map))
                          "- DB hash:" (hash db-before))
        {:keys [db issues session-updates]} (api/dispatch db-before current-session intent-map)
        db-after db
        should-log? (not (contains? #{:inspect-dataspex :clear-log} (:type intent-map)))]
    (when (seq issues)
      (js/console.error "Intent validation failed:" (pr-str issues)))

    ;; CRITICAL: Apply session updates BEFORE DB changes!
    ;; The DB reset triggers Replicant re-render, which fires on-mount hooks.
    ;; Those hooks read session state (cursor-position), so session must be updated first.
    (when session-updates
      (session/swap-session! #(merge-with merge % session-updates)))

    ;; Apply DB changes (triggers re-render)
    (reset! !db db-after)

    ;; DEBUG: Assert derived indexes are fresh after reset
    (assert-derived-fresh! db-after (str "after NEXUS dispatch: " (:type intent-map)))

    ;; DEBUG: Log after reset for context-aware-enter
    (when (= (:type intent-map) :context-aware-enter)
      (js/console.log "🔍 AFTER context-aware-enter - DB hash:" (hash @!db)
                      "\n  children-by-parent keys:" (pr-str (keys (:children-by-parent @!db))))
      ;; Check for UUID blocks and their parents
      (doseq [[child parent] (get-in @!db [:derived :parent-of])]
        (when (str/starts-with? (str child) "block-")
          (let [children-of-parent (get-in @!db [:children-by-parent parent] [])
                in-children? (some #{child} children-of-parent)]
            (js/console.log "📊 Block parent:" child "→" parent
                            "| in children?:" (boolean in-children?))))))

    ;; Log to devtools
    (when should-log?
      (dev/log-dispatch! intent-map db-before db-after))))

(defn log-devtools
  "Effect that logs actions + intents to window.__nexusLog (dev only)."
  [ctx _ action-data]
  (when ^boolean goog.DEBUG
    (let [timestamp (js/Date.now)
          log-entry (merge {:timestamp timestamp}
                           action-data)]
      (when-not (exists? js/window.__nexusLog)
        (set! js/window.__nexusLog #js []))
      (.push js/window.__nexusLog (clj->js log-entry)))))

;; ── Placeholders ──────────────────────────────────────────────────────────────

(defn event-selection-start
  "Placeholder: DOM selection start offset."
  [{:keys [dispatch-data]}]
  (when-let [e (:dom-event dispatch-data)]
    (try
      (let [sel (.getSelection js/window)]
        (when sel
          (.-anchorOffset sel)))
      (catch :default _
        nil))))

(defn event-selection-end
  "Placeholder: DOM selection end offset."
  [{:keys [dispatch-data]}]
  (when-let [e (:dom-event dispatch-data)]
    (try
      (let [sel (.getSelection js/window)]
        (when sel
          (.-focusOffset sel)))
      (catch :default _
        nil))))

(defn event-caret-row
  "Placeholder: Cursor row position (:first/:middle/:last).
   
   Provided by component handler, not computed here."
  [{:keys [dispatch-data]}]
  (:cursor-row dispatch-data))

(defn event-target-value
  "Placeholder: Input element value."
  [{:keys [dispatch-data]}]
  (when-let [e (:dom-event dispatch-data)]
    (some-> e .-target .-value)))

(defn event-block-id
  "Placeholder: Block ID from dispatch data."
  [{:keys [dispatch-data]}]
  (:block-id dispatch-data))

(defn event-direction
  "Placeholder: Selection direction (:forward/:backward)."
  [{:keys [dispatch-data]}]
  (:direction dispatch-data))

;; ── Actions ───────────────────────────────────────────────────────────────────

(defn navigate-up
  "Action: Navigate cursor up from current block.

   Dispatches :navigate-with-cursor-memory intent."
  [state {:keys [block-id current-text current-cursor-pos cursor-row]}]
  [[:effects/dispatch-intent {:type :navigate-with-cursor-memory
                              :current-block-id block-id
                              :current-text current-text
                              :current-cursor-pos current-cursor-pos
                              :direction :up}]
   [:effects/log-devtools {:action :editing/navigate-up
                           :block-id block-id
                           :cursor-row cursor-row}]])

(defn navigate-down
  "Action: Navigate cursor down from current block."
  [state {:keys [block-id current-text current-cursor-pos cursor-row]}]
  [[:effects/dispatch-intent {:type :navigate-with-cursor-memory
                              :current-block-id block-id
                              :current-text current-text
                              :current-cursor-pos current-cursor-pos
                              :direction :down}]
   [:effects/log-devtools {:action :editing/navigate-down
                           :block-id block-id
                           :cursor-row cursor-row}]])

(defn extend-selection-prev
  "Action: Extend block selection upward (Shift+Arrow up at boundary)."
  [state {:keys [block-id direction]}]
  [[:effects/dispatch-intent {:type :selection
                              :mode :extend-prev}]
   [:effects/log-devtools {:action :selection/extend-prev
                           :block-id block-id
                           :direction direction}]])

(defn extend-selection-next
  "Action: Extend block selection downward (Shift+Arrow down at boundary)."
  [state {:keys [block-id direction]}]
  [[:effects/dispatch-intent {:type :selection
                              :mode :extend-next}]
   [:effects/log-devtools {:action :selection/extend-next
                           :block-id block-id
                           :direction direction}]])

(defn smart-split
  "Action: Smart split block (Enter key)."
  [state {:keys [block-id cursor-pos]}]
  [[:effects/dispatch-intent {:type :context-aware-enter
                              :block-id block-id
                              :cursor-pos cursor-pos}]
   [:effects/log-devtools {:action :editing/smart-split
                           :block-id block-id
                           :cursor-pos cursor-pos}]])

(defn escape-edit
  "Action: Exit edit mode (Escape key)."
  [state {:keys [block-id]}]
  [[:effects/dispatch-intent {:type :exit-edit
                              :block-id block-id}]
   [:effects/log-devtools {:action :editing/escape
                           :block-id block-id}]])

;; ── Registry Setup ────────────────────────────────────────────────────────────

(defn init!
  "Initialize Nexus registry with effects, actions, and placeholders.
   
   Call this once on app startup."
  []
  ;; System→State: dereference the !db atom
  (nxr/register-system->state! deref)

  ;; Effects
  (nxr/register-effect! :effects/dispatch-intent dispatch-intent)
  (nxr/register-effect! :effects/log-devtools log-devtools)

  ;; Placeholders
  (nxr/register-placeholder! :event.selection/start-offset event-selection-start)
  (nxr/register-placeholder! :event.selection/end-offset event-selection-end)
  (nxr/register-placeholder! :event.caret/row event-caret-row)
  (nxr/register-placeholder! :event.target/value event-target-value)
  (nxr/register-placeholder! :event/block-id event-block-id)
  (nxr/register-placeholder! :event/direction event-direction)

  ;; Actions
  (nxr/register-action! :editing/navigate-up navigate-up)
  (nxr/register-action! :editing/navigate-down navigate-down)
  (nxr/register-action! :selection/extend-prev extend-selection-prev)
  (nxr/register-action! :selection/extend-next extend-selection-next)
  (nxr/register-action! :editing/smart-split smart-split)
  (nxr/register-action! :editing/escape escape-edit)

  ;; Dev observability: Initialize window.__nexusLog array
  (when ^boolean goog.DEBUG
    (set! js/window.__nexusLog #js [])))

;; ── Dispatch Façade ───────────────────────────────────────────────────────────

(defn dispatch!
  "Dispatch Nexus actions.
   
   Args:
     !db          - DB atom (system)
     actions      - Action vector(s) from Replicant
     dispatch-data - DOM event + context from Replicant
   
   Usage:
     (dispatch! !db [[:editing/navigate-up {:block-id \"a\" :cursor-row :last}]]
                    {:dom-event e})"
  [!db dispatch-data actions]
  (nxr/dispatch !db dispatch-data actions))
