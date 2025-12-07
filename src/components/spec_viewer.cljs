(ns components.spec-viewer
  "Spec-as-UI: Interactive documentation for Functional Requirements.
   
   Renders specs.edn as browsable documentation with:
   - FR registry browser (filterable by priority/type/tags)
   - Tree DSL visualizer (setup → action → expect)
   - Before/After diff view for scenarios
   
   Usage: Add ?specs to URL to show spec viewer"
  (:require [spec-registry :as fr]
            [clojure.string :as str]
            [replicant.dom :as d]))

;; ══════════════════════════════════════════════════════════════════════════════
;; State
;; ══════════════════════════════════════════════════════════════════════════════

(defonce !ui-state
  (atom {:selected-fr nil
         :selected-scenario nil
         :filter {:priority nil :type nil :tag nil}
         :show-all? false ; false = only show FRs with executable scenarios
         :show-dsl? false ; false = outline view, true = raw DSL syntax
         :expanded #{}}))

(defonce render-scheduled? (atom false))

(declare SpecViewer)

(defn- request-render!
  "Request a render on the next animation frame.
   Multiple calls in the same frame are coalesced into one render."
  []
  (when-not @render-scheduled?
    (reset! render-scheduled? true)
    (js/requestAnimationFrame
     (fn []
       (reset! render-scheduled? false)
       (d/render (js/document.getElementById "root")
                 (SpecViewer))))))

;; Note: Render is triggered by blocks_ui when it detects spec mode
;; We just need to trigger a re-render when our state changes
(defonce _watcher
  (add-watch !ui-state :render
             (fn [_ _ old new]
               (when (not= old new)
                 (request-render!)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Styles
;; ══════════════════════════════════════════════════════════════════════════════

(def styles
  "Devtools-inspired aesthetic: dark panels, syntax highlighting, clear hierarchy."
  {;; Layout
   :container {:display "flex"
               :height "100vh"
               :font-family "'IBM Plex Sans', system-ui, sans-serif"
               :font-size "14px"
               :background "#0f0f10"}

   :sidebar {:width "280px"
             :border-right "1px solid #2a2a2e"
             :overflow-y "auto"
             :background "#18181b"}

   :main {:flex "1"
          :overflow-y "auto"
          :padding "24px 32px"
          :background "#0f0f10"}

   ;; Sidebar items
   :fr-item {:padding "12px 16px"
             :cursor "pointer"
             :border-bottom "1px solid #27272a"
             :transition "background 0.15s ease"}

   :fr-item-selected {:background "#3f3f46"}

   :fr-title {:font-weight "500"
              :font-size "13px"
              :color "#fafafa"
              :font-family "'IBM Plex Mono', monospace"}

   :fr-desc {:font-size "11px"
             :color "#71717a"
             :margin-top "4px"
             :line-height "1.4"}

   ;; Priority badges
   :priority-badge {:display "inline-block"
                    :padding "3px 8px"
                    :border-radius "3px"
                    :font-size "10px"
                    :font-weight "600"
                    :letter-spacing "0.5px"
                    :font-family "'IBM Plex Mono', monospace"}

   :priority-critical {:background "#7f1d1d" :color "#fecaca"}
   :priority-high {:background "#7c2d12" :color "#fed7aa"}
   :priority-medium {:background "#713f12" :color "#fef08a"}
   :priority-low {:background "#14532d" :color "#bbf7d0"}

   ;; Scenario card
   :scenario-card {:border "1px solid #27272a"
                   :border-radius "8px"
                   :margin-bottom "20px"
                   :background "#18181b"
                   :overflow "hidden"}

   :scenario-header {:padding "14px 18px"
                     :background "#1f1f23"
                     :border-bottom "1px solid #27272a"
                     :display "flex"
                     :justify-content "space-between"
                     :align-items "center"}

   :scenario-body {:padding "0"}

   ;; Before/After panels
   :diff-container {:display "grid"
                    :grid-template-columns "1fr 1fr"
                    :gap "0"}

   :diff-panel {:padding "16px 18px"
                :min-height "120px"}

   :diff-panel-before {:background "#18181b"
                       :border-right "1px solid #27272a"}

   :diff-panel-after {:background "#1a1a1f"}

   :diff-label {:font-size "10px"
                :font-weight "600"
                :letter-spacing "1px"
                :text-transform "uppercase"
                :margin-bottom "10px"
                :display "flex"
                :align-items "center"
                :gap "6px"}

   :diff-label-before {:color "#a1a1aa"}
   :diff-label-after {:color "#22c55e"}

   ;; Action banner (between before/after)
   :action-banner {:background "linear-gradient(90deg, #3730a3 0%, #4f46e5 50%, #6366f1 100%)"
                   :padding "10px 18px"
                   :font-family "'IBM Plex Mono', monospace"
                   :font-size "12px"
                   :color "#e0e7ff"
                   :display "flex"
                   :align-items "center"
                   :gap "10px"}

   :action-arrow {:font-size "16px"
                  :color "#a5b4fc"}

;; (tree-viz style removed - using inline styles in outline view)

;; (run buttons and status badges removed - scenarios are for documentation only)

   ;; Tags
   :tag {:display "inline-block"
         :background "#27272a"
         :color "#a1a1aa"
         :padding "3px 8px"
         :border-radius "3px"
         :font-size "10px"
         :font-family "'IBM Plex Mono', monospace"
         :margin-right "6px"}})

;; ══════════════════════════════════════════════════════════════════════════════
;; Tree Visualizer
;; ══════════════════════════════════════════════════════════════════════════════

;; format-attrs removed - using outline view instead

(defn- render-cursor-in-text
  "Render text with a visual cursor indicator at the specified position."
  [text cursor-pos]
  (let [pos (if (= cursor-pos :end) (count text) cursor-pos)
        before (subs text 0 (min pos (count text)))
        after (subs text (min pos (count text)))]
    [:span
     [:span before]
     [:span {:style {:background "#3b82f6"
                     :width "2px"
                     :display "inline-block"
                     :height "1.1em"
                     :vertical-align "text-bottom"
                     :margin "0 1px"
                     :animation "blink 1s step-end infinite"}} ""]
     [:span after]]))

(defn- extract-block-info
  "Extract block info from a tree entry for row-based comparison."
  [entry]
  (when (and (vector? entry) (seq entry))
    (let [[id text & rest] entry
          attrs (first (filter map? rest))
          children (filter vector? rest)]
      {:id id
       :text (or text "")
       :cursor (:cursor attrs)
       :selected? (:selected? attrs)
       :focus? (:focus? attrs)
       :anchor? (:anchor? attrs)
       :folded? (:folded? attrs)
       :children (mapv extract-block-info children)})))

(defn- flatten-blocks
  "Flatten tree into sequence of [depth block-info] pairs."
  [block-info depth]
  (when block-info
    (cons [depth block-info]
          (mapcat #(flatten-blocks % (inc depth)) (:children block-info)))))

(defn- render-block-cell
  "Render a single block cell (used in both before/after columns)."
  [block-info depth]
  (let [{:keys [id text cursor selected? focus? anchor? folded?]} block-info
        indent-px (* depth 14)]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :padding "4px 10px"
                   :padding-left (str (+ 10 indent-px) "px")
                   :background (cond
                                 focus? "rgba(59, 130, 246, 0.12)"
                                 selected? "rgba(59, 130, 246, 0.06)"
                                 :else "transparent")
                   :min-height "28px"}}
     ;; Bullet - BIGGER
     [:span {:style {:color (cond cursor "#3b82f6" selected? "#60a5fa" :else "#52525b")
                     :margin-right "10px"
                     :font-size "10px"}}
      (if folded? "▶" "●")]
     ;; Block ID
     [:span {:style {:background "#27272a"
                     :color "#a1a1aa"
                     :padding "2px 6px"
                     :border-radius "3px"
                     :font-size "10px"
                     :margin-right "10px"
                     :min-width "16px"
                     :text-align "center"}}
      (name id)]
     ;; Text with cursor
     [:span {:style {:color "#e4e4e7" :font-size "13px"}}
      (if cursor
        (render-cursor-in-text text cursor)
        text)]
     ;; State badges (compact)
     (when (or cursor focus? selected? anchor?)
       [:span {:style {:display "flex" :gap "4px" :margin-left "10px"}}
        (when cursor
          [:span {:style {:background "#1e3a8a" :color "#93c5fd" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}}
           (if (= cursor :end) "▮end" (str "▮" cursor))])
        (when focus?
          [:span {:style {:background "#166534" :color "#86efac" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "focus"])
        (when (and selected? (not focus?))
          [:span {:style {:background "#1e3a5f" :color "#7dd3fc" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "sel"])
        (when anchor?
          [:span {:style {:background "#4c1d95" :color "#c4b5fd" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "anchor"])])]))

(defn TreeVisualizer
  "Visualize a tree DSL structure as an outline."
  [{:keys [tree]}]
  (when tree
    (let [[_root & entries] tree
          blocks (mapcat #(flatten-blocks (extract-block-info %) 0) entries)]
      [:div {:style {:font-family "'IBM Plex Mono', monospace"}}
       [:style "@keyframes blink { 50% { opacity: 0; } }"]
       (for [[i [depth block-info]] (map-indexed vector blocks)]
         ^{:key i}
         (render-block-cell block-info depth))])))

(defn- render-dsl-tree
  "Render tree DSL with syntax highlighting (raw Clojure notation)."
  [tree depth]
  (when (and (vector? tree) (seq tree))
    (let [[tag & rest] tree
          text (first (filter string? rest))
          attrs (first (filter map? rest))
          children (filter vector? rest)
          indent (apply str (repeat (* depth 2) " "))
          kw-color "#c678dd"
          str-color "#98c379"
          attr-color "#e5c07b"
          bracket-color "#6b7280"]
      [:div {:style {:white-space "pre" :line-height "1.5"}}
       [:span {:style {:color "#4b5563"}} indent]
       [:span {:style {:color bracket-color}} "["]
       [:span {:style {:color kw-color}} (str ":" (name tag))]
       (when text
         [:span {:style {:color str-color}} (str " \"" text "\"")])
       (when (seq attrs)
         [:span {:style {:color attr-color}} (str " " (pr-str attrs))])
       (when (empty? children)
         [:span {:style {:color bracket-color}} "]"])
       (for [[i child] (map-indexed vector children)]
         ^{:key i} (render-dsl-tree child (inc depth)))
       (when (seq children)
         [:div {:style {:white-space "pre"}}
          [:span {:style {:color "#4b5563"}} indent]
          [:span {:style {:color bracket-color}} "]"]])])))

(defn- DslView
  "Raw DSL syntax view with syntax highlighting."
  [{:keys [tree]}]
  (when tree
    [:div {:style {:font-family "'IBM Plex Mono', monospace"
                   :font-size "12px"
                   :background "#1a1a1f"
                   :padding "12px"
                   :border-radius "4px"}}
     (render-dsl-tree tree 0)]))

(defn- DslDiffView
  "Side-by-side DSL syntax comparison."
  [{:keys [before after]}]
  [:div {:style {:display "grid"
                 :grid-template-columns "1fr auto 1fr"
                 :gap "12px"
                 :align-items "start"}}
   [:div
    [:div {:style {:color "#71717a" :font-size "10px" :text-transform "uppercase"
                   :letter-spacing "0.5px" :margin-bottom "8px"}}
     "Before"]
    (DslView {:tree before})]
   [:div {:style {:color "#4b5563" :font-size "16px" :padding-top "24px"}} "→"]
   [:div
    [:div {:style {:color "#22c55e" :font-size "10px" :text-transform "uppercase"
                   :letter-spacing "0.5px" :margin-bottom "8px"}}
     "After"]
    (DslView {:tree after})]])

(defn DiffView
  "Row-aligned before/after comparison - minimizes eye movement.
   Columns shrink to fit content, keeping before/after close together."
  [{:keys [before after]}]
  (let [show-dsl? (:show-dsl? @!ui-state)
        before-blocks (when before
                        (let [[_root & entries] before]
                          (vec (mapcat #(flatten-blocks (extract-block-info %) 0) entries))))
        after-blocks (when after
                       (let [[_root & entries] after]
                         (vec (mapcat #(flatten-blocks (extract-block-info %) 0) entries))))
        max-rows (max (count before-blocks) (count after-blocks))]
    (if show-dsl?
      ;; DSL syntax view
      (DslDiffView {:before before :after after})
      ;; Outline view (default)
      [:div {:style {:font-family "'IBM Plex Mono', monospace"
                     :font-size "13px"
                     :display "inline-block"}}
       [:style "@keyframes blink { 50% { opacity: 0; } }"]
       [:table {:style {:border-collapse "collapse"}}
        [:thead
         [:tr
          [:th {:style {:color "#71717a" :font-size "11px" :text-transform "uppercase"
                        :letter-spacing "0.5px" :padding "0 12px 8px 0"
                        :font-weight "500" :text-align "left"}}
           "Before"]
          [:th {:style {:color "#4b5563" :padding "0 16px 8px" :font-weight "normal"}}
           "→"]
          [:th {:style {:color "#22c55e" :font-size "11px" :text-transform "uppercase"
                        :letter-spacing "0.5px" :padding "0 0 8px 0"
                        :font-weight "500" :text-align "left"}}
           "After"]]]
        [:tbody
         (for [i (range max-rows)]
           (let [[before-depth before-block] (get before-blocks i)
                 [after-depth after-block] (get after-blocks i)]
             ^{:key i}
             [:tr {:style {:border-top "1px solid #1f1f23"}}
              [:td {:style {:padding "0" :vertical-align "middle"}}
               (when before-block
                 (render-block-cell before-block before-depth))]
              [:td {:style {:padding "0 12px"
                            :color "#4b5563"
                            :font-size "14px"
                            :vertical-align "middle"
                            :text-align "center"}}
               (when (or before-block after-block) "→")]
              [:td {:style {:padding "0" :vertical-align "middle"}}
               (when after-block
                 (render-block-cell after-block after-depth))]]))]]])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scenario Card
;; ══════════════════════════════════════════════════════════════════════════════

;; run-scenario! removed - scenarios are for documentation, not interactive execution

(defn ScenarioCard
  "Render a scenario with row-aligned before/after diff view."
  [{:keys [scenario-id scenario]}]
  (let [{:keys [setup action expect]} scenario
        safe-name (fn [x] (cond (keyword? x) (name x)
                                (string? x) x
                                :else (str x)))
        executable? (and setup action expect)]
    [:div {:style (:scenario-card styles)}
     ;; Header
     [:div {:style (:scenario-header styles)}
      [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
       (when executable?
         [:span {:style {:color "#22c55e" :font-size "10px"}} "●"])
       [:span {:style {:font-weight "600" :font-size "13px" :color "#fafafa"
                       :font-family "'IBM Plex Mono', monospace"}}
        (safe-name scenario-id)]
       [:span {:style {:color "#71717a" :font-size "12px"}}
        (:name scenario)]]
      (when-let [tags (:tags scenario)]
        [:div {:style {:display "flex" :gap "4px"}}
         (for [tag (if (set? tags) (sort tags) tags)]
           ^{:key (str tag)}
           [:span {:style (:tag styles)} (safe-name tag)])])]

     ;; Row-aligned diff view (Tufte-style: minimal saccades)
     (when executable?
       [:div
        [:div {:style {:padding "12px 16px"}}
         (DiffView {:before (:tree setup) :after (:tree expect)})]

        ;; Compact action banner
        [:div {:style {:background "linear-gradient(90deg, #312e81 0%, #3730a3 100%)"
                       :padding "8px 16px"
                       :font-family "'IBM Plex Mono', monospace"
                       :font-size "11px"
                       :color "#c7d2fe"
                       :display "flex"
                       :align-items "center"
                       :gap "8px"}}
         [:span {:style {:color "#818cf8" :font-weight "600" :font-size "9px"
                         :text-transform "uppercase" :letter-spacing "0.5px"}}
          "action"]
         [:span {:style {:color "#6366f1"}} "→"]
         [:span (str ":" (safe-name (:type action))
                     (when-let [dir (:direction action)] (str " " (safe-name dir)))
                     (when-let [pos (:current-cursor-pos action)] (str " from col " pos)))]]])]))

;; ══════════════════════════════════════════════════════════════════════════════
;; FR Detail Panel
;; ══════════════════════════════════════════════════════════════════════════════

(defn FRDetail
  "Detailed view of a single FR with all its scenarios."
  [{:keys [fr-id]}]
  (when-let [fr (fr/get-fr fr-id)]
    (let [all-scenarios (:scenarios fr)
          scenarios (into {} (filter (fn [[_ s]] (fr/executable-scenario? s)) all-scenarios))
          scenario-count (count scenarios)
          show-dsl? (:show-dsl? @!ui-state)]
      [:div
       ;; Header
       [:div {:style {:margin-bottom "28px"}}
        [:h2 {:style {:margin "0 0 8px 0"
                      :font-size "22px"
                      :font-weight "600"
                      :color "#fafafa"
                      :font-family "'IBM Plex Mono', monospace"}}
         (name fr-id)]
        [:p {:style {:color "#a1a1aa" :margin "0" :line-height "1.5"}}
         (:desc fr)]

        ;; Metadata row
        [:div {:style {:margin-top "14px" :display "flex" :gap "12px" :flex-wrap "wrap" :align-items "center"}}
         [:span {:style (merge (:priority-badge styles)
                               (case (:priority fr)
                                 :critical (:priority-critical styles)
                                 :high (:priority-high styles)
                                 :medium (:priority-medium styles)
                                 :low (:priority-low styles)
                                 {}))}
          (str/upper-case (name (:priority fr)))]
         [:span {:style {:font-size "12px" :color "#71717a"}}
          (str "Type: " (name (:type fr)))]
         [:span {:style {:font-size "12px" :color "#71717a"}}
          (str "Spec: " (:spec-ref fr))]
         (when (pos? scenario-count)
           [:span {:style {:display "flex" :align-items "center" :gap "4px"
                           :font-size "12px" :color "#22c55e"}}
            "●" (str scenario-count " scenario" (when (> scenario-count 1) "s"))])]]

       ;; Behaviors table
       (when-let [behaviors (:behaviors fr)]
         [:div {:style {:margin-bottom "28px"}}
          [:h3 {:style {:font-size "13px" :font-weight "600" :color "#a1a1aa"
                        :margin-bottom "12px" :text-transform "uppercase" :letter-spacing "0.5px"}}
           "Behaviors"]
          [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"
                           :background "#18181b" :border-radius "6px" :overflow "hidden"}}
           [:thead
            [:tr {:style {:background "#1f1f23"}}
             [:th {:style {:text-align "left" :padding "10px 14px" :color "#71717a" :font-weight "500" :border-bottom "1px solid #27272a"}} "Context"]
             [:th {:style {:text-align "left" :padding "10px 14px" :color "#71717a" :font-weight "500" :border-bottom "1px solid #27272a"}} "Behavior"]
             [:th {:style {:text-align "left" :padding "10px 14px" :color "#71717a" :font-weight "500" :border-bottom "1px solid #27272a"}} "Scenario"]]]
           [:tbody
            (for [[i beh] (map-indexed vector behaviors)]
              ^{:key i}
              [:tr
               [:td {:style {:padding "10px 14px" :border-bottom "1px solid #27272a" :color "#d4d4d8"}} (:context beh)]
               [:td {:style {:padding "10px 14px" :border-bottom "1px solid #27272a" :color "#d4d4d8"}} (:behavior beh)]
               [:td {:style {:padding "10px 14px" :border-bottom "1px solid #27272a"}}
                (when-let [s (:scenario beh)]
                  [:code {:style {:background "#27272a" :color "#a1a1aa" :padding "3px 8px"
                                  :border-radius "3px" :font-family "'IBM Plex Mono', monospace" :font-size "11px"}}
                   (name s)])]])]]])

       ;; Scenarios section with view toggle
       (when (seq scenarios)
         [:div
          [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" :margin-bottom "16px"}}
           [:h3 {:style {:font-size "13px" :font-weight "600" :color "#a1a1aa" :margin "0"
                         :text-transform "uppercase" :letter-spacing "0.5px"}}
            (str "Scenarios (" scenario-count ")")]
           ;; View toggle: Outline / DSL
           [:div {:style {:display "flex" :gap "4px" :background "#27272a" :padding "3px" :border-radius "4px"}}
            [:button {:style {:background (if-not show-dsl? "#3f3f46" "transparent")
                              :border "none" :color (if-not show-dsl? "#fafafa" "#71717a")
                              :padding "4px 10px" :border-radius "3px" :cursor "pointer"
                              :font-size "11px" :font-family "'IBM Plex Mono', monospace"}
                      :on {:click (fn [_] (swap! !ui-state assoc :show-dsl? false))}}
             "Outline"]
            [:button {:style {:background (if show-dsl? "#3f3f46" "transparent")
                              :border "none" :color (if show-dsl? "#fafafa" "#71717a")
                              :padding "4px 10px" :border-radius "3px" :cursor "pointer"
                              :font-size "11px" :font-family "'IBM Plex Mono', monospace"}
                      :on {:click (fn [_] (swap! !ui-state assoc :show-dsl? true))}}
             "DSL"]]]

          ;; Scenario cards
          (for [[scenario-id scenario] scenarios]
            ^{:key scenario-id}
            (ScenarioCard {:scenario-id scenario-id
                           :scenario scenario}))])])))

;; ══════════════════════════════════════════════════════════════════════════════
;; FR Sidebar
;; ══════════════════════════════════════════════════════════════════════════════

(defn FRSidebar
  "Sidebar listing all FRs, filterable."
  []
  (let [state @!ui-state
        selected (:selected-fr state)
        show-all? (:show-all? state)
        all-frs (fr/list-frs)
        ;; Filter to only FRs with executable scenarios unless show-all?
        frs (if show-all?
              all-frs
              (filter fr/has-executable-scenarios? all-frs))
        executable-count (count (filter fr/has-executable-scenarios? all-frs))
        by-priority (group-by #(:priority (fr/get-fr %)) frs)
        priority-order [:critical :high :medium :low]]
    [:div {:style (:sidebar styles)}
     ;; Header
     [:div {:style {:padding "18px 16px"
                    :border-bottom "1px solid #27272a"}}
      [:h1 {:style {:margin "0"
                    :font-size "15px"
                    :font-weight "600"
                    :color "#fafafa"}}
       "Spec Registry"]
      [:div {:style {:color "#71717a"
                     :font-size "11px"
                     :margin-top "4px"
                     :font-family "'IBM Plex Mono', monospace"}}
       (if show-all?
         (str (count frs) " FRs · " (fr/scenario-count) " scenarios")
         (str executable-count "/" (count all-frs) " with scenarios"))]

      ;; Toggle
      [:div {:style {:margin-top "10px"
                     :display "flex"
                     :align-items "center"
                     :gap "8px"}}
       [:button {:style {:background (if show-all? "#3f3f46" "#27272a")
                         :border "1px solid #3f3f46"
                         :border-radius "4px"
                         :padding "4px 8px"
                         :font-size "10px"
                         :color (if show-all? "#fafafa" "#71717a")
                         :cursor "pointer"
                         :font-family "'IBM Plex Mono', monospace"}
                 :on {:click (fn [_] (swap! !ui-state update :show-all? not))}}
        (if show-all? "All FRs" "Testable")]]]

     ;; FR list by priority
     (for [priority priority-order
           :let [fr-ids (get by-priority priority)]
           :when (seq fr-ids)]
       ^{:key priority}
       [:div
        [:div {:style {:padding "8px 16px"
                       :background "#0f0f10"
                       :font-size "10px"
                       :font-weight "600"
                       :color "#52525b"
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :border-bottom "1px solid #27272a"}}
         (str (name priority) " (" (count fr-ids) ")")]
        (for [fr-id (sort fr-ids)]
          (let [fr (fr/get-fr fr-id)
                is-selected (= fr-id selected)
                has-scenarios? (fr/has-executable-scenarios? fr-id)]
            ^{:key fr-id}
            [:div {:style (merge (:fr-item styles)
                                 (when is-selected (:fr-item-selected styles)))
                   :on {:click (fn [_] (swap! !ui-state assoc :selected-fr fr-id))}}
             [:div {:style {:display "flex" :align-items "center" :gap "6px"}}
              [:span {:style (:fr-title styles)}
               (name fr-id)]
              (when has-scenarios?
                [:span {:style {:color "#22c55e" :font-size "8px"}} "●"])]
             [:div {:style (:fr-desc styles)}
              (let [desc (:desc fr)]
                (if (> (count desc) 55)
                  (str (subs desc 0 52) "...")
                  desc))]]))])]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Component
;; ══════════════════════════════════════════════════════════════════════════════

(defn SpecViewer
  "Main spec viewer component."
  []
  (let [state @!ui-state
        selected-fr (:selected-fr state)]
    [:div {:style (:container styles)}
     ;; Font import
     [:style "
       @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&display=swap');
     "]

     ;; Sidebar
     (FRSidebar)

     ;; Main content
     [:div {:style (:main styles)}
      (if selected-fr
        (FRDetail {:fr-id selected-fr})
        ;; Welcome screen
        [:div {:style {:text-align "center"
                       :padding "80px 20px"
                       :max-width "480px"
                       :margin "0 auto"}}
         [:div {:style {:font-size "48px" :margin-bottom "20px"}} "⚗"]
         [:h2 {:style {:color "#fafafa"
                       :margin-bottom "12px"
                       :font-size "20px"
                       :font-weight "600"}}
          "Spec-as-UI"]
         [:p {:style {:color "#71717a" :line-height "1.6" :margin-bottom "24px"}}
          "Select a Functional Requirement from the sidebar to view behaviors and run executable scenarios."]
         [:div {:style {:display "flex"
                        :flex-direction "column"
                        :gap "8px"
                        :text-align "left"
                        :background "#18181b"
                        :border-radius "8px"
                        :padding "16px 20px"
                        :font-size "13px"}}
          [:div {:style {:display "flex" :gap "10px" :color "#a1a1aa"}}
           [:span {:style {:color "#c678dd"}} "→"]
           "FR metadata & priority"]
          [:div {:style {:display "flex" :gap "10px" :color "#a1a1aa"}}
           [:span {:style {:color "#c678dd"}} "→"]
           "Context → Behavior mapping"]
          [:div {:style {:display "flex" :gap "10px" :color "#a1a1aa"}}
           [:span {:style {:color "#c678dd"}} "→"]
           "Before / After scenario diffs"]]])]]))
