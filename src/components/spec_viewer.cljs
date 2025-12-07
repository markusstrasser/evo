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

(defn- outline-block
  "Render a single block in outline style with visual indicators."
  [entry depth is-last?]
  (when (and (vector? entry) (seq entry))
    (let [[id text & rest] entry
          attrs (first (filter map? rest))
          children (filter vector? rest)
          ;; Extract state from attrs
          cursor-pos (:cursor attrs)
          selected? (:selected? attrs)
          focus? (:focus? attrs)
          anchor? (:anchor? attrs)
          folded? (:folded? attrs)
          ;; Visual styling
          indent-px (* depth 20)
          has-children? (seq children)]
      [:div {:style {:position "relative"}}
       ;; The block row
       [:div {:style {:display "flex"
                      :align-items "flex-start"
                      :padding "4px 0"
                      :padding-left (str indent-px "px")
                      :background (cond
                                    focus? "rgba(59, 130, 246, 0.15)"
                                    selected? "rgba(59, 130, 246, 0.08)"
                                    :else "transparent")
                      :border-radius "4px"
                      :margin "1px 0"}}
        ;; Tree connector lines
        (when (pos? depth)
          [:span {:style {:color "#3f3f46"
                          :margin-right "6px"
                          :font-size "12px"}}
           (if is-last? "└" "├")])
        ;; Bullet point
        [:span {:style {:color (cond
                                 cursor-pos "#3b82f6" ; editing = blue
                                 selected? "#60a5fa" ; selected = lighter blue
                                 :else "#52525b") ; normal = gray
                        :margin-right "8px"
                        :font-size "8px"
                        :line-height "1.8"}}
         (if folded? "▸" "•")]
        ;; Block ID badge
        [:span {:style {:background "#27272a"
                        :color "#a1a1aa"
                        :padding "1px 5px"
                        :border-radius "3px"
                        :font-size "10px"
                        :margin-right "8px"
                        :font-family "'IBM Plex Mono', monospace"}}
         (name id)]
        ;; Block text with optional cursor
        [:span {:style {:color "#e4e4e7"
                        :flex "1"}}
         (if cursor-pos
           (render-cursor-in-text (or text "") cursor-pos)
           (or text ""))]
        ;; State indicators on the right
        [:span {:style {:display "flex" :gap "4px" :margin-left "8px"}}
         (when cursor-pos
           [:span {:style {:background "#1e40af"
                           :color "#93c5fd"
                           :padding "1px 6px"
                           :border-radius "3px"
                           :font-size "9px"
                           :font-family "'IBM Plex Mono', monospace"}}
            (if (= cursor-pos :end) "end" (str "pos:" cursor-pos))])
         (when focus?
           [:span {:style {:background "#166534"
                           :color "#86efac"
                           :padding "1px 6px"
                           :border-radius "3px"
                           :font-size "9px"}}
            "focus"])
         (when (and selected? (not focus?))
           [:span {:style {:background "#1e3a5f"
                           :color "#7dd3fc"
                           :padding "1px 6px"
                           :border-radius "3px"
                           :font-size "9px"}}
            "sel"])
         (when anchor?
           [:span {:style {:background "#4c1d95"
                           :color "#c4b5fd"
                           :padding "1px 6px"
                           :border-radius "3px"
                           :font-size "9px"}}
            "anchor"])]]
       ;; Render children
       (when (seq children)
         [:div
          (for [[i child] (map-indexed vector children)]
            ^{:key i}
            (outline-block child (inc depth) (= i (dec (count children)))))])])))

(defn TreeVisualizer
  "Visualize a tree DSL structure as an outline (like the actual editor UI)."
  [{:keys [tree]}]
  (when tree
    (let [[_root & entries] tree]
      [:div {:style {:font-family "'IBM Plex Mono', monospace"
                     :font-size "12px"
                     :line-height "1.5"}}
       ;; Add CSS for cursor blink animation
       [:style "@keyframes blink { 50% { opacity: 0; } }"]
       (for [[i entry] (map-indexed vector entries)]
         ^{:key i}
         (outline-block entry 0 (= i (dec (count entries)))))])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scenario Card
;; ══════════════════════════════════════════════════════════════════════════════

;; run-scenario! removed - scenarios are for documentation, not interactive execution

(defn ScenarioCard
  "Render a scenario with Before/After diff view."
  [{:keys [scenario-id scenario]}]
  (let [{:keys [setup action expect]} scenario
        ;; Safe name extraction - handles keywords, strings, or nil
        safe-name (fn [x] (cond (keyword? x) (name x)
                                (string? x) x
                                :else (str x)))
        ;; Check if this is a complete executable scenario
        executable? (and setup action expect)]
    [:div {:style (:scenario-card styles)}
     ;; Header with green dot for executable scenarios
     [:div {:style (:scenario-header styles)}
      [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
       ;; Green dot indicator
       (when executable?
         [:span {:style {:color "#22c55e" :font-size "10px"}} "●"])
       [:span {:style {:font-weight "600"
                       :font-size "13px"
                       :color "#fafafa"
                       :font-family "'IBM Plex Mono', monospace"}}
        (safe-name scenario-id)]
       [:span {:style {:color "#71717a" :font-size "12px"}}
        (:name scenario)]]
      ;; Tags on right side
      (when-let [tags (:tags scenario)]
        [:div {:style {:display "flex" :gap "4px"}}
         (for [tag (if (set? tags) (sort tags) tags)]
           ^{:key (str tag)}
           [:span {:style (:tag styles)} (safe-name tag)])])]

     ;; Before/After diff view (only for complete scenarios)
     (when executable?
       [:div
        [:div {:style (:diff-container styles)}
         ;; BEFORE panel
         [:div {:style (merge (:diff-panel styles) (:diff-panel-before styles))}
          [:div {:style (merge (:diff-label styles) (:diff-label-before styles))}
           [:span {:style {:opacity "0.6"}} "○"]
           "BEFORE"]
          (TreeVisualizer {:tree (:tree setup)})]

         ;; AFTER panel  
         [:div {:style (merge (:diff-panel styles) (:diff-panel-after styles))}
          [:div {:style (merge (:diff-label styles) (:diff-label-after styles))}
           [:span {:style {:opacity "0.8"}} "●"]
           "AFTER"]
          (TreeVisualizer {:tree (:tree expect)})]]

        ;; Action banner
        [:div {:style (:action-banner styles)}
         [:span {:style {:color "#a5b4fc" :font-weight "600"}} "ACTION"]
         [:span {:style (:action-arrow styles)} "→"]
         [:span {:style {:color "#e0e7ff"}}
          (str ":" (safe-name (:type action)))
          (when-let [bid (:block-id action)]
            (str " on :" bid))
          (when-let [dir (:direction action)]
            (str " " (safe-name dir)))
          (when-let [pos (:cursor-pos action)]
            (str " at pos " pos))
          (when-let [pos (:current-cursor-pos action)]
            (str " from col " pos))]]])]))

;; ══════════════════════════════════════════════════════════════════════════════
;; FR Detail Panel
;; ══════════════════════════════════════════════════════════════════════════════

(defn FRDetail
  "Detailed view of a single FR with all its scenarios."
  [{:keys [fr-id]}]
  (when-let [fr (fr/get-fr fr-id)]
    (let [all-scenarios (:scenarios fr)
          ;; Only show executable scenarios (with setup/action/expect)
          scenarios (into {} (filter (fn [[_ s]] (fr/executable-scenario? s)) all-scenarios))
          scenario-count (count scenarios)]
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

        ;; Metadata
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
         ;; Coverage indicator
         (when (pos? scenario-count)
           [:span {:style {:display "flex"
                           :align-items "center"
                           :gap "4px"
                           :font-size "12px"
                           :color "#22c55e"}}
            "●"
            (str scenario-count " scenario" (when (> scenario-count 1) "s"))])]]

       ;; Behaviors table
       (when-let [behaviors (:behaviors fr)]
         [:div {:style {:margin-bottom "28px"}}
          [:h3 {:style {:font-size "13px"
                        :font-weight "600"
                        :color "#a1a1aa"
                        :margin-bottom "12px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
           "Behaviors"]
          [:table {:style {:width "100%"
                           :border-collapse "collapse"
                           :font-size "13px"
                           :background "#18181b"
                           :border-radius "6px"
                           :overflow "hidden"}}
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
                  [:code {:style {:background "#27272a"
                                  :color "#a1a1aa"
                                  :padding "3px 8px"
                                  :border-radius "3px"
                                  :font-family "'IBM Plex Mono', monospace"
                                  :font-size "11px"}}
                   (name s)])]])]]])

       ;; Scenarios (only executable ones)
       (when (seq scenarios)
         [:div
          [:h3 {:style {:font-size "13px"
                        :font-weight "600"
                        :color "#a1a1aa"
                        :margin-bottom "16px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
           (str "Scenarios (" scenario-count ")")]

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
