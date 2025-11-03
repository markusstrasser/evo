(ns debug
  "REPL debugging helpers - load in browser console or REPL"
  (:require [shell.blocks-ui :as app]
            [kernel.db :as db]
            [kernel.query :as q]
            [kernel.history :as H]
            [kernel.api :as api]
            [cljs.pprint :refer [pprint]]))

;; ── State Inspection ──────────────────────────────────────────────────────────

(defn state
  "Get current app DB state"
  []
  @app/!db)

(defn nodes
  "Get all nodes"
  []
  (:nodes (state)))

(defn node
  "Get specific node by ID"
  [id]
  (get-in (state) [:nodes id]))

(defn children
  "Get children of a node"
  [parent-id]
  (q/children (state) parent-id))

(defn parent
  "Get parent of a node"
  [node-id]
  (q/parent-of (state) node-id))

(defn selection
  "Get current selection"
  []
  (q/selection (state)))

(defn editing
  "Get currently editing block ID"
  []
  (q/editing-block-id (state)))

(defn folded
  "Get set of folded block IDs"
  []
  (q/folded-blocks (state)))

;; ── History Inspection ────────────────────────────────────────────────────────

(defn history
  "Get history map"
  []
  (H/get-history (state)))

(defn undo-count
  "Get number of undo steps available"
  []
  (H/undo-count (state)))

(defn redo-count
  "Get number of redo steps available"
  []
  (H/redo-count (state)))

(defn can-undo?
  "Check if undo is available"
  []
  (H/can-undo? (state)))

(defn can-redo?
  "Check if redo is available"
  []
  (H/can-redo? (state)))

;; ── Summary Functions ─────────────────────────────────────────────────────────

(defn summary
  "Print state summary"
  []
  (let [s (state)]
    {:nodes/total (count (nodes))
     :nodes/blocks (count (filter #(= :block (:type %)) (vals (nodes))))
     :nodes/pages (count (filter #(= :page (:type %)) (vals (nodes))))
     :selection/count (count (selection))
     :selection/ids (vec (selection))
     :editing/block-id (editing)
     :folded/count (count (folded))
     :history/undo-count (undo-count)
     :history/redo-count (redo-count)}))

(defn tree
  "Print tree structure starting from root"
  ([] (tree :doc))
  ([root-id]
   (letfn [(print-tree [id depth]
             (let [n (node id)
                   text (get-in n [:props :text] "")
                   title (get-in n [:props :title] "")
                   label (if (seq text) text title)
                   indent (apply str (repeat (* depth 2) " "))]
               (println (str indent "- " id " (" (:type n) "): " label))
               (doseq [child-id (children id)]
                 (print-tree child-id (inc depth)))))]
     (print-tree root-id 0))))

;; ── Action Helpers ────────────────────────────────────────────────────────────

(defn reload!
  "Hard reload the page"
  []
  (.reload js/location true))

(defn clear-db!
  "Reset to empty DB (WARNING: destructive)"
  []
  (when (js/confirm "Reset to empty DB? This will lose all data.")
    (reset! app/!db (db/empty-db))
    (println "✅ DB reset to empty")))

(defn dispatch!
  "Dispatch an intent and show trace"
  [intent-map]
  (js/console.log "Dispatching intent:" (pr-str intent-map))
  (let [{:keys [db trace]} (api/dispatch @app/!db intent-map)]
    (js/console.log "Trace:" (pr-str trace))
    (reset! app/!db db)
    trace))

;; ── Integrity Checks ──────────────────────────────────────────────────────────

(defn check-integrity!
  "Check DB integrity and print report"
  []
  (let [s (state)
        {:keys [ok? issues]} (db/validate s)]
    (if ok?
      (do
        (js/console.log "✅ No integrity issues found")
        {:ok? true :issues []})
      (do
        (js/console.log "⚠️  Found" (count issues) "issue(s):")
        (doseq [issue issues]
          (js/console.log "  ❌" (pr-str issue)))
        {:ok? false :issues issues}))))

;; ── Export to window for console access ──────────────────────────────────────

(defn ^:export init! []
  (js/console.log "🔧 Debug helpers loaded. Try:")
  (js/console.log "  DEBUG.summary()         - State overview")
  (js/console.log "  DEBUG.tree()            - Print tree structure")
  (js/console.log "  DEBUG.checkIntegrity()  - Validate DB")
  (js/console.log "  DEBUG.nodes             - All nodes")
  (js/console.log "  DEBUG.selection()       - Current selection")
  (js/console.log "  DEBUG.history()         - History state")
  (js/console.log "  DEBUG.reload()          - Hard reload")
  (set! js/window.DEBUG
        #js {:state state
             :nodes nodes
             :node node
             :children children
             :parent parent
             :selection selection
             :editing editing
             :folded folded
             :history history
             :undoCount undo-count
             :redoCount redo-count
             :canUndo can-undo?
             :canRedo can-redo?
             :summary summary
             :tree tree
             :checkIntegrity check-integrity!
             :dispatch dispatch!
             :reload reload!
             :clearDb clear-db!}))

;; Auto-initialize when preloaded
(init!)
