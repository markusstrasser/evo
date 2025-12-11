(ns components.devtools
  "Dev tools UI component for debugging state and operations."
  (:require [dev.tooling :as devtools]
            [kernel.query :as q]
            [kernel.history :as H]
            [shell.view-state :as vs]
            [clojure.string :as str]))

;; ── Style helpers ─────────────────────────────────────────────────────────────

(def ^:private panel-style
  "Shared style for panel wrappers."
  {:margin-bottom "20px"
   :padding "15px"
   :background-color "#ffffff"
   :border "1px solid #e5e7eb"
   :border-radius "6px"
   :box-shadow "0 1px 3px rgba(0,0,0,0.1)"})

(def ^:private panel-header-style
  "Shared style for panel headers."
  {:display "flex"
   :justify-content "space-between"
   :align-items "center"
   :margin-bottom "15px"})

(def ^:private panel-title-style
  "Shared style for panel h3 titles."
  {:margin 0
   :font-size "16px"
   :color "#1f2937"})

(def ^:private code-block-style
  "Shared style for code/pre blocks."
  {:margin 0
   :padding "10px"
   :background-color "#1f2937"
   :color "#f3f4f6"
   :border-radius "4px"
   :overflow-x "auto"
   :font-size "11px"
   :white-space "pre-wrap"})

(def ^:private base-button-style
  "Base button styling - merge with color."
  {:padding "6px 12px"
   :color "white"
   :border "none"
   :border-radius "4px"
   :cursor "pointer"
   :font-size "12px"})

(defn- button-style
  "Generate button style with specific background color."
  [bg-color & {:keys [small? bold?]}]
  (cond-> (assoc base-button-style :background-color bg-color)
    small? (assoc :padding "4px 8px" :border-radius "3px" :font-size "11px")
    bold? (assoc :font-weight "600")))

(def ^:private empty-state-style
  "Style for empty state messages."
  {:text-align "center"
   :padding "30px"
   :color "#9ca3af"})

;; ── Components ────────────────────────────────────────────────────────────────

(defn LogEntry
  "Single log entry with copy button that includes both ops and DOM diff."
  [{:keys [entry index current-page-id]}]
  (let [{:keys [intent timestamp hotkey]} entry
        timestamp-obj (js/Date. timestamp)
        time-str (.toLocaleTimeString timestamp-obj "en-US" #js {:hour "2-digit" :minute "2-digit" :second "2-digit"})]
    [:div.log-entry
     {:style {:padding "10px"
              :margin-bottom "8px"
              :background-color (if (even? index) "#f8f9fa" "#ffffff")
              :border-left "3px solid #4f46e5"
              :border-radius "4px"
              :font-family "monospace"
              :font-size "12px"}}
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :align-items "flex-start"
                    :margin-bottom "6px"}}
      [:div
       [:span {:style {:color "#4f46e5" :font-weight "bold"}}
        time-str " → " (devtools/format-intent intent)]
       (when hotkey
         [:div {:style {:margin-top "4px"
                        :font-size "11px"
                        :color "#6b7280"
                        :background-color "#e5e7eb"
                        :display "inline-block"
                        :padding "2px 6px"
                        :border-radius "3px"}}
          "⌨️ " hotkey])]
      [:button
       {:on {:click (fn [_]
                      (devtools/copy-to-clipboard!
                       (devtools/format-entry-with-diff entry current-page-id))
                      (js/alert "Ops + DOM diff copied to clipboard!"))}
        :style (button-style "#4f46e5" :small? true)}
       "📋 Copy All"]]
     [:details
      [:summary {:style {:cursor "pointer" :color "#6b7280"}}
       "Show before/after"]
      [:pre {:style (assoc code-block-style :margin-top "8px" :padding "8px")}
       (devtools/format-entry-with-diff entry current-page-id)]]]))

(defn OpsLogPanel
  "Panel showing operation log with copy functionality."
  [{:keys [db]}]
  (let [log (devtools/get-log)
        log-text (devtools/format-full-log)
        current-page-id (vs/current-page)]
    [:div.ops-log-panel
     {:style panel-style}
     [:div {:style panel-header-style}
      [:h3 {:style panel-title-style}
       "📋 Operations Log "
       [:span {:style {:color "#6b7280" :font-weight "normal" :font-size "13px"}}
        "(" (count log) " entries)"]]
      [:div {:style {:display "flex" :gap "8px"}}
       [:button
        {:on {:click (fn [_]
                       (devtools/copy-to-clipboard! log-text)
                       (js/alert "Full log copied to clipboard!"))}
         :style (button-style "#10b981" :bold? true)}
        "📋 Copy All"]
       [:button
        {:on {:click (fn [_]
                       (devtools/clear-log!)
                       (js/alert "Log cleared!"))}
         :style (button-style "#ef4444")}
        "🗑 Clear"]]]

     (if (empty? log)
       [:div {:style empty-state-style}
        "No operations yet. Start editing to see logs!"]
       [:div.log-entries
        {:style {:max-height "400px"
                 :overflow-y "auto"}}
        (->> log
             reverse
             (map-indexed (fn [i entry]
                            ^{:key i}
                            [LogEntry {:entry entry :index i :current-page-id current-page-id}]))
             doall)])]))

(defn DOMDiffPanel
  "Panel showing hiccup/DOM differences."
  [{:keys [db]}]
  (let [log (devtools/get-log)
        last-entry (last log)]
    [:div.dom-diff-panel
     {:style panel-style}
     [:div {:style panel-header-style}
      [:h3 {:style panel-title-style}
       "🔍 DOM State Viewer"]
      (when last-entry
        [:button
         {:on {:click (fn [_]
                        (let [current-page (vs/current-page)
                              hiccup-before (devtools/extract-hiccup-tree
                                             (:db-before last-entry)
                                             current-page)
                              hiccup-after (devtools/extract-hiccup-tree
                                            (:db-after last-entry)
                                            current-page)
                              diff-text (devtools/format-hiccup-diff hiccup-before hiccup-after)]
                          (devtools/copy-to-clipboard! diff-text)
                          (js/alert "DOM diff copied to clipboard!")))}
          :style (button-style "#3b82f6")}
         "📋 Copy Last Diff"])]

     (if last-entry
       (let [_current-page (vs/current-page) ; TODO: use for page-specific diff view
             state-before (devtools/format-state-snapshot (:db-before last-entry))
             state-after (devtools/format-state-snapshot (:db-after last-entry))
             scrollable-code-style (assoc code-block-style
                                          :line-height "1.6"
                                          :max-height "300px"
                                          :overflow-y "auto")]
         [:div {:style {:display "grid"
                        :grid-template-columns "1fr 1fr"
                        :gap "15px"}}
          [:div
           [:h4 {:style {:margin "0 0 8px 0"
                         :font-size "13px"
                         :color "#6b7280"}}
            "BEFORE"]
           [:pre {:style scrollable-code-style}
            state-before]]
          [:div
           [:h4 {:style {:margin "0 0 8px 0"
                         :font-size "13px"
                         :color "#6b7280"}}
            "AFTER"]
           [:pre {:style scrollable-code-style}
            state-after]]])
       [:div {:style empty-state-style}
        "No state changes yet."])]))

(defn StateSnapshot
  "Current state snapshot (compact view)."
  [{:keys [db]}]
  [:div.state-snapshot
   {:style {:padding "12px"
            :background-color "#f9fafb"
            :border-radius "4px"
            :font-family "monospace"
            :font-size "12px"
            :margin-bottom "20px"}}
   [:div {:style {:display "grid"
                  :grid-template-columns "repeat(3, 1fr)"
                  :gap "10px"}}
    [:div
     [:strong "Selection: "]
     [:span {:style {:color "#4f46e5"}}
      (let [sel (q/selection db)]
        (if (empty? sel) "∅" (str/join ", " sel)))]]
    [:div
     [:strong "Focus: "]
     [:span {:style {:color "#4f46e5"}}
      (or (q/focus db) "—")]]
    [:div
     [:strong "Editing: "]
     [:span {:style {:color (if (q/editing-block-id db) "#10b981" "#9ca3af")}}
      (or (q/editing-block-id db) "—")]]]
   [:div {:style {:display "flex"
                  :gap "15px"
                  :margin-top "10px"}}
    [:div
     [:strong "Can undo: "]
     [:span {:style {:color (if (H/can-undo? db) "#10b981" "#ef4444")}}
      (str (H/can-undo? db))]]
    [:div
     [:strong "Can redo: "]
     [:span {:style {:color (if (H/can-redo? db) "#10b981" "#ef4444")}}
      (str (H/can-redo? db))]]]])

(defn DevToolsPanel
  "Main dev tools panel with all debugging info."
  [{:keys [db]}]
  [:div.devtools-panel
   {:style {:margin-top "30px"
            :padding "20px"
            :background-color "#fafafa"
            :border-radius "8px"
            :border "2px solid #e5e7eb"}}
   [:h2 {:style {:margin "0 0 20px 0"
                 :font-size "20px"
                 :color "#111827"}}
    "🛠 Dev Tools"]

   ;; Current state snapshot
   [StateSnapshot {:db db}]

   ;; Operations log
   [OpsLogPanel {:db db}]

   ;; DOM diff viewer
   [DOMDiffPanel {:db db}]

   ;; REPL helpers
   [:div.repl-helpers
    {:style {:padding "15px"
             :background-color "#fef3c7"
             :border-left "4px solid #f59e0b"
             :border-radius "4px"}}
    [:h4 {:style {:margin "0 0 10px 0"
                  :font-size "14px"
                  :color "#92400e"}}
     "💻 REPL Helpers"]
    [:div {:style {:font-family "monospace"
                   :font-size "12px"
                   :color "#78350f"}}
     [:div "(dev.tooling/get-log) — Get full log history"]
     [:div "(dev.tooling/print-last 5) — Print last 5 entries"]
     [:div "(dev.tooling/clear-log!) — Clear log"]
     [:div "(dev.tooling/export-log) — Export as EDN"]]]])
