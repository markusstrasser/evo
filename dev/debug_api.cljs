(ns debug-api
  "Queryable debug API exposed on window.DEBUG for AI tools and E2E tests.
   
   Provides programmatic access to:
   - Dispatch log (last N operations, find by type)
   - Undo/redo stack inspection
   - Clipboard operation tracking
   - State assertions with structured results
   
   Usage from browser console or Playwright:
     window.DEBUG.lastIntent()
     window.DEBUG.undoCount()
     window.DEBUG.assertBlockText('block-1', 'expected text')"
  (:require [dev.tooling :as tooling]
            [kernel.history :as H]
            [shell.view-state :as vs]))

;; ─── Dispatch Log Queries ───────────────────────────────────────────────────

(defn last-intent
  "Get the last dispatched intent."
  []
  (when-let [entry (last (tooling/get-log))]
    (:intent entry)))

(defn last-n-entries
  "Get the last N dispatch log entries."
  [n]
  (->> (tooling/get-log)
       (take-last n)
       vec))

(defn find-intents
  "Find all dispatch entries matching an intent type (keyword or string)."
  [intent-type]
  (let [type-kw (if (keyword? intent-type) intent-type (keyword intent-type))]
    (->> (tooling/get-log)
         (filter (fn [{:keys [intent]}]
                   (or (= intent type-kw)
                       (and (map? intent) (= (:type intent) type-kw)))))
         vec)))

(defn intent-counts
  "Get counts of each intent type in the log."
  []
  (->> (tooling/get-log)
       (map (fn [{:keys [intent]}]
              (cond
                (keyword? intent) intent
                (map? intent) (:type intent)
                :else :unknown)))
       frequencies))

;; ─── Undo/Redo Stack Inspection ─────────────────────────────────────────────

(defn undo-count
  "Number of available undo steps."
  [db]
  (H/undo-count db))

(defn redo-count
  "Number of available redo steps."
  [db]
  (H/redo-count db))

(defn can-undo?
  "Check if undo is available."
  [db]
  (H/can-undo? db))

(defn can-redo?
  "Check if redo is available."
  [db]
  (H/can-redo? db))

(defn undo-stack-summary
  "Get a summary of the undo stack (not full DB snapshots)."
  [db]
  (let [history (H/get-history db)
        past (:past history)]
    (mapv (fn [idx]
            {:index idx
             :node-count (count (get-in past [idx :db :nodes]))})
          (range (count past)))))

;; ─── State Assertions ───────────────────────────────────────────────────────
;; Return structured results: {:ok bool :reason string :actual value}

(defn assert-block-text
  "Assert a block has expected text."
  [db block-id expected-text]
  (let [actual (get-in db [:nodes block-id :props :text])]
    (if (= actual expected-text)
      {:ok true :block-id block-id}
      {:ok false
       :reason (str "Block " block-id " text mismatch")
       :expected expected-text
       :actual actual})))

(defn assert-selection
  "Assert the current selection matches expected IDs."
  [expected-ids]
  (let [actual (vs/selection-nodes)
        expected-set (set (if (sequential? expected-ids)
                            expected-ids
                            [expected-ids]))]
    (if (= actual expected-set)
      {:ok true :selection (vec actual)}
      {:ok false
       :reason "Selection mismatch"
       :expected (vec expected-set)
       :actual (vec actual)})))

(defn assert-editing
  "Assert a specific block is being edited (or nil for not editing)."
  [expected-block-id]
  (let [actual (vs/editing-block-id)]
    (if (= actual expected-block-id)
      {:ok true :editing actual}
      {:ok false
       :reason "Editing state mismatch"
       :expected expected-block-id
       :actual actual})))

(defn assert-undoable
  "Assert that undo is available."
  [db]
  (if (H/can-undo? db)
    {:ok true :undo-count (H/undo-count db)}
    {:ok false
     :reason "No undo available"
     :undo-count 0}))

(defn assert-last-intent
  "Assert the last intent was of a specific type."
  [expected-type]
  (let [last-entry (last (tooling/get-log))
        actual-type (when last-entry
                      (let [intent (:intent last-entry)]
                        (cond
                          (keyword? intent) intent
                          (map? intent) (:type intent)
                          :else nil)))
        expected-kw (if (keyword? expected-type)
                      expected-type
                      (keyword expected-type))]
    (if (= actual-type expected-kw)
      {:ok true :intent-type actual-type}
      {:ok false
       :reason "Last intent type mismatch"
       :expected expected-kw
       :actual actual-type})))

;; ─── State Snapshots ────────────────────────────────────────────────────────

(defn full-snapshot
  "Get a complete snapshot of app state for debugging."
  [db]
  {:timestamp (js/Date.now)
   :db {:node-count (count (:nodes db))
        :page-count (count (filter #(= :page (:type (val %))) (:nodes db)))
        :roots (:roots db)}
   :session {:editing (vs/editing-block-id)
             :selection (vec (vs/selection-nodes))
             :focus (vs/focus-id)
             :folded-count (count (vs/folded))
             :current-page (vs/current-page)}
   :history {:undo-count (H/undo-count db)
             :redo-count (H/redo-count db)}
   :log {:entry-count (count (tooling/get-log))
         :last-intent (last-intent)}
   :clipboard {:entry-count (count (tooling/get-clipboard-log))
               :last-op (tooling/last-clipboard-op)}})

;; ─── Window API Installation ────────────────────────────────────────────────

(defn install!
  "Install DEBUG API on window object. Call from editor.cljs main."
  [db-atom]
  (set! (.-DEBUG js/window)
        #js {;; ─── Dispatch Log ─────────────────────────────────
             :lastIntent (fn [] (clj->js (last-intent)))
             :lastN (fn [n] (clj->js (last-n-entries n)))
             :findIntents (fn [type-str] (clj->js (find-intents type-str)))
             :intentCounts (fn [] (clj->js (intent-counts)))
             :clearLog (fn [] (tooling/clear-log!) nil)
             :fullLog (fn [] (clj->js (tooling/get-log)))

             ;; ─── Undo/Redo ────────────────────────────────────
             :undoCount (fn [] (undo-count @db-atom))
             :redoCount (fn [] (redo-count @db-atom))
             :canUndo (fn [] (can-undo? @db-atom))
             :canRedo (fn [] (can-redo? @db-atom))
             :undoStack (fn [] (clj->js (undo-stack-summary @db-atom)))

             ;; ─── Clipboard ────────────────────────────────────
             :lastCopy (fn [] (clj->js (tooling/last-clipboard-op)))
             :clipboardLog (fn [] (clj->js (tooling/get-clipboard-log)))
             :clearClipboardLog (fn [] (tooling/clear-clipboard-log!) nil)

             ;; ─── Assertions ───────────────────────────────────
             :assertBlockText (fn [block-id expected]
                                (clj->js (assert-block-text @db-atom block-id expected)))
             :assertSelection (fn [ids-js]
                                (clj->js (assert-selection (js->clj ids-js))))
             :assertEditing (fn [block-id]
                              (clj->js (assert-editing block-id)))
             :assertUndoable (fn []
                               (clj->js (assert-undoable @db-atom)))
             :assertLastIntent (fn [type-str]
                                 (clj->js (assert-last-intent type-str)))

             ;; ─── Snapshots ────────────────────────────────────
             :snapshot (fn [] (clj->js (full-snapshot @db-atom)))

             ;; ─── Utilities ────────────────────────────────────
             :getDb (fn [] (clj->js @db-atom))
             :getSession (fn [] (clj->js (vs/get-view-state)))
             :blockText (fn [block-id]
                          (get-in @db-atom [:nodes block-id :props :text]))})

  (js/console.log "🔍 DEBUG API installed. Try: window.DEBUG.snapshot()"))
