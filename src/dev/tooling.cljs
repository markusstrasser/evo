(ns dev.tooling
  "Developer-visible logging helpers consumed by components.devtools.

   Stores a rolling log of dispatches (intent, DB before/after, optional hotkey)
   so humans/agents can inspect ops without leaving the browser."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [shell.view-state :as vs]))

(def ^:const max-entries 200)
(defonce !log (atom []))

(defn- summarize-db [db]
  (let [nodes (:nodes db)
        pages (keep (fn [[id {:keys [type]}]]
                      (when (= type :page) id))
                    nodes)
        ;; Read from session atom (not from deprecated DB nodes)
        selection (vs/selection-nodes)
        editing (vs/editing-block-id)]
    {:node-count (count nodes)
     :page-count (count pages)
     :selection-count (count selection)
     :selection selection
     :editing editing}))

(defn- clip-log [entries]
  (->> entries
       (take-last max-entries)
       vec))

(defn log-dispatch!
  "Record an intent dispatch with before/after DB snapshots.
   Returns nil so callers can thread through swap!/effect code."
  ([intent db-before db-after]
   (log-dispatch! intent db-before db-after nil))
  ([intent db-before db-after hotkey]
   (let [entry {:intent intent
                :hotkey hotkey
                :timestamp (js/Date.now)
                :db-before db-before
                :db-after db-after
                :summary {:before (summarize-db db-before)
                          :after (summarize-db db-after)}}]
     (swap! !log (fn [entries]
                   (clip-log (conj entries entry))))
     nil)))

(defn format-intent [intent]
  (cond
    (keyword? intent) (name intent)
    (and (map? intent) (:type intent)) (name (:type intent))
    :else (pr-str intent)))

(defn copy-to-clipboard! [text]
  (when (and (exists? js/navigator)
             (exists? js/navigator.clipboard))
    (.writeText js/navigator.clipboard (str text))))

(defn- delta-summary [{:keys [before after]}]
  {:nodes (- (:node-count after) (:node-count before))
   :pages (- (:page-count after) (:page-count before))
   :selection (- (:selection-count after) (:selection-count before))})

(defn format-entry-with-diff [{:keys [intent hotkey timestamp summary]} current-page-id]
  (let [;; Read current session state (not historical - we no longer track session in DB history)
        editing (vs/editing-block-id)
        selection (vs/selection-nodes)]
    (with-out-str
      (pprint/pprint
       {:intent intent
        :hotkey hotkey
        :timestamp timestamp
        :page current-page-id
        :delta (delta-summary summary)
        :editing editing
        :selection selection}))))

(defn get-log []
  @!log)

(defn format-full-log []
  (->> @!log
       (map #(format-entry-with-diff % nil))
       (str/join "\n")))

(defn clear-log! []
  (reset! !log [])
  nil)

(defn- block->snapshot [db id]
  (let [node (get-in db [:nodes id])
        children (get-in db [:children-by-parent id] [])]
    {:id id
     :type (:type node)
     :text (or (get-in node [:props :title])
               (get-in node [:props :text]))
     :children (map #(block->snapshot db %) children)}))

(defn extract-hiccup-tree [db page-id]
  (let [root (or page-id :doc)
    children (get-in db [:children-by-parent root] [])]
    (map #(block->snapshot db %) children)))

(defn format-hiccup-diff [before after]
  (cond
    (and before after)
    (with-out-str
      (println "--- BEFORE ---")
      (pprint/pprint before)
      (println "--- AFTER ---")
      (pprint/pprint after))

    before
    (with-out-str
      (println "--- BEFORE ---")
      (pprint/pprint before))

    after
    (with-out-str
      (println "--- AFTER ---")
      (pprint/pprint after))

    :else
    "No DOM snapshot available"))

(defn format-state-snapshot [db]
  (let [{:keys [node-count page-count editing selection-count]} (summarize-db db)
        editing-text (or editing "—")]
    (str node-count " nodes | " page-count " pages | editing=" editing-text " | selection=" selection-count)))
