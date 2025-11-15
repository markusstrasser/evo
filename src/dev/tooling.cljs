(ns dev.tooling
  "Development tools for debugging state changes and DOM updates.

   Features:
   - Human-readable ops log with DSL notation
   - DOM diff viewer (hiccup before/after)
   - Clipboard copy functionality
   - REPL access to logs"
  (:require [kernel.query :as q]
            [plugins.pages :as pages]
            [clojure.string :as str]
            [cljs.pprint]))

;; ── State Log (Ring Buffer) ──────────────────────────────────────────────────

;; Ring buffer of last 100 state transitions.
;; Each entry: {:intent, :ops, :db-before, :db-after, :timestamp, :hotkey}
(defonce !log-history (atom []))

(def MAX-LOG-ENTRIES 100)

(defn- add-log-entry! [entry]
  (swap! !log-history
         (fn [log]
           (let [new-log (conj log entry)]
             (if (> (count new-log) MAX-LOG-ENTRIES)
               (vec (drop 1 new-log))
               new-log)))))

(defn log-dispatch!
  "Record a dispatch event for dev tools.
   Call this before applying intent."
  ([intent db-before db-after]
   (log-dispatch! intent db-before db-after nil))
  ([intent db-before db-after hotkey]
   (add-log-entry!
    {:intent intent
     :db-before db-before
     :db-after db-after
     :hotkey hotkey
     :timestamp (js/Date.now)})))

(defn get-log
  "Get full log history (for REPL access)."
  []
  @!log-history)

(defn clear-log!
  "Clear log history."
  []
  (reset! !log-history []))

;; ── Human-Readable Formatting ────────────────────────────────────────────────

(defn format-state-snapshot
  "Format DB state as human-readable snapshot using DSL notation.
   Shows: mode (E/V), tree structure, selection, cursor position.

   Follows notation from docs/VISUAL_DSL_COMMUNICATION.md:
   - Edit mode: Shows |text for editing block (cursor pos not tracked in DB)
   - View mode: Shows -*text for selected, ^ for anchor, ~ for focus"
  [db]
  (let [editing-id (q/editing-block-id db)
        selection (q/selection db)
        focus (q/focus db)
        anchor (q/anchor db)
        current-page (pages/current-page db)

        ;; Build tree representation
        format-node (fn format-node [id depth]
                      (let [node (get-in db [:nodes id])
                            children (get-in db [:children-by-parent id] [])
                            text (get-in node [:props :text] "")
                            selected? (contains? selection id)
                            is-focus (= id focus)
                            is-anchor (= id anchor)
                            is-editing? (= id editing-id)

                            indent (str/join (repeat depth "  "))

                            ;; In edit mode, show |text to indicate editing (exact cursor pos not in DB)
                            ;; In view mode, show selection markers
                            display-text (cond
                                          is-editing? (str "|" (or text id))
                                          (not (str/blank? text)) text
                                          :else id)

                            prefix (cond
                                     selected? (str indent "-*")
                                     :else (str indent "-"))

                            ;; Anchor and focus markers (view mode only)
                            suffix (cond
                                     ;; Both anchor and focus (single block selected)
                                     (and is-anchor is-focus) " ^~"
                                     ;; Just anchor
                                     is-anchor " ^"
                                     ;; Just focus
                                     is-focus " ~"
                                     ;; Nothing
                                     :else "")

                            line (str prefix " " display-text suffix "\n")]
                        (str line
                             (str/join (map #(format-node % (inc depth)) children)))))]

    (str (if editing-id ":E\n" ":V\n")
         (when current-page
           (format-node current-page 0)))))

(defn format-intent
  "Format intent as human-readable string."
  [intent]
  (let [type (:type intent)]
    (case type
      :selection (str "SELECT " (:mode intent)
                      (when (:ids intent) (str " → " (:ids intent))))
      :enter-edit (str "ENTER-EDIT " (:block-id intent))
      :exit-edit "EXIT-EDIT"
      :update-content (str "UPDATE-TEXT " (:block-id intent) " → \"" (:text intent) "\"")
      :create-block "CREATE-BLOCK"
      :delete-selected "DELETE-SELECTED"
      :indent-selected "INDENT"
      :outdent-selected "OUTDENT"
      :move-selected-up "MOVE-UP"
      :move-selected-down "MOVE-DOWN"
      :toggle-fold (str "TOGGLE-FOLD " (:block-id intent))
      :undo "UNDO"
      :redo "REDO"
      ;; Default: show full intent map
      (pr-str intent))))

(defn format-op
  "Format a single operation with clear visual design."
  [op]
  (case (:op op)
    :create-node (str "  ➕ CREATE " (:type op) " " (:id op)
                      (when (:props op) (str " — " (pr-str (:props op)))))
    :update-node (str "  ✏️  UPDATE " (:id op)
                      (when (:props op) (str " — " (pr-str (:props op)))))
    :place (str "  📍 PLACE " (:id op) " under " (:under op) " at " (:at op))
    :delete (str "  🗑 DELETE " (:id op))
    ;; Default
    (str "  " (pr-str op))))

(defn extract-ops-from-intent
  "Extract operations that would be generated by intent (simplified - just show intent structure)."
  [intent]
  ;; For now, just show intent as one operation line
  ;; In full version, you'd call the intent handler to get actual ops
  [(str "  ⚡ " (format-intent intent))])

(defn format-log-entry
  "Format a log entry as human-readable text with hotkey and ops."
  [{:keys [intent db-before db-after timestamp hotkey]}]
  (let [timestamp-obj (js/Date. timestamp)
        time-str (.toLocaleTimeString timestamp-obj)
        ops-lines (extract-ops-from-intent intent)]
    (str "═══ " time-str " ═══\n"
         (when hotkey (str "Hotkey: " hotkey "\n"))
         "Intent: " (format-intent intent) "\n"
         "\nOperations:\n"
         (str/join "\n" ops-lines)
         "\n\n"
         "BEFORE:\n"
         (format-state-snapshot db-before)
         "\n"
         "AFTER:\n"
         (format-state-snapshot db-after)
         "\n")))

(defn format-full-log
  "Format entire log as copyable text."
  []
  (->> (get-log)
       (map format-log-entry)
       (str/join "\n\n")))

;; ── DOM Diff Formatting ───────────────────────────────────────────────────────

(defn extract-hiccup-tree
  "Extract hiccup tree from DB for given root."
  [db root-id]
  ;; Simplified - just show block structure
  (letfn [(build-tree [id]
            (let [node (get-in db [:nodes id])
                  children (get-in db [:children-by-parent id] [])
                  text (get-in node [:props :text] "")]
              [:div {:data-id id}
               [:span.bullet "•"]
               [:span.content text]
               (when (seq children)
                 (into [:div.children]
                       (map build-tree children)))]))]
    (build-tree root-id)))

(defn format-hiccup-diff
  "Format hiccup before/after as readable diff."
  [before after]
  (str "BEFORE:\n"
       (with-out-str (cljs.pprint/pprint before))
       "\n\n"
       "AFTER:\n"
       (with-out-str (cljs.pprint/pprint after))))

(defn format-entry-with-diff
  "Format log entry with both ops and HTML diff for copy-paste debugging."
  [entry current-page-id]
  (let [hiccup-before (extract-hiccup-tree (:db-before entry) current-page-id)
        hiccup-after (extract-hiccup-tree (:db-after entry) current-page-id)
        diff (format-hiccup-diff hiccup-before hiccup-after)]
    (str (format-log-entry entry)
         "\n\n"
         "═══ DOM DIFF ═══\n"
         diff)))

;; ── Clipboard Utilities ───────────────────────────────────────────────────────

(defn copy-to-clipboard!
  "Copy text to clipboard (browser API)."
  [text]
  (try
    (.writeText (.-clipboard js/navigator) text)
    (js/console.log "Copied to clipboard!")
    true
    (catch js/Error e
      (js/console.error "Copy failed:" e)
      false)))

;; ── REPL Utilities ────────────────────────────────────────────────────────────

(defn print-log
  "Print full log to console (for REPL)."
  []
  (js/console.log (format-full-log)))

(defn print-last
  "Print last N log entries (default 1)."
  ([] (print-last 1))
  ([n]
   (let [entries (take-last n (get-log))]
     (doseq [entry entries]
       (js/console.log (format-log-entry entry))))))

(defn export-log
  "Export log as EDN for analysis."
  []
  (pr-str (map #(select-keys % [:intent :timestamp]) (get-log))))
