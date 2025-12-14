(ns components.sidebar
  "Sidebar component for page navigation.

   Design: Editorial refinement - clean hierarchy, subtle depth,
   sophisticated micro-interactions. Inspired by Logseq's left sidebar
   but with distinctive character."
  (:require [kernel.query :as q]
            [shell.view-state :as vs]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- journal-page?
  "Detect if a page title looks like a journal date.
   Matches patterns like 'Dec 14th, 2025' or '2025-12-14'."
  [title]
  (when title
    (or (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}" title)
        (re-matches #"\d{4}-\d{2}-\d{2}" title))))

(defn- untitled?
  "Check if page has default untitled name."
  [title]
  (or (nil? title)
      (str/blank? title)
      (= title "Untitled")))

;; ── Icons (inline SVG for crisp rendering) ───────────────────────────────────

(defn- Icon
  "Minimal SVG icons. size in pixels."
  [{:keys [name size] :or {size 16}}]
  (let [props {:width size :height size :viewBox "0 0 24 24"
               :fill "none" :stroke "currentColor" :stroke-width "1.5"
               :stroke-linecap "round" :stroke-linejoin "round"}]
    (case name
      :file [:svg props
             [:path {:d "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"}]
             [:polyline {:points "14 2 14 8 20 8"}]]
      :calendar [:svg props
                 [:rect {:x "3" :y "4" :width "18" :height "18" :rx "2" :ry "2"}]
                 [:line {:x1 "16" :y1 "2" :x2 "16" :y2 "6"}]
                 [:line {:x1 "8" :y1 "2" :x2 "8" :y2 "6"}]
                 [:line {:x1 "3" :y1 "10" :x2 "21" :y2 "10"}]]
      :folder [:svg props
               [:path {:d "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"}]]
      :folder-open [:svg props
                    [:path {:d "M5 19a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4l2 2h9a2 2 0 0 1 2 2v1"}]
                    [:path {:d "M5 19l2.5-7h13l2.5 7H5z"}]]
      :plus [:svg props
             [:line {:x1 "12" :y1 "5" :x2 "12" :y2 "19"}]
             [:line {:x1 "5" :y1 "12" :x2 "19" :y2 "12"}]]
      :more [:svg props
             [:circle {:cx "12" :cy "12" :r "1"}]
             [:circle {:cx "19" :cy "12" :r "1"}]
             [:circle {:cx "5" :cy "12" :r "1"}]]
      :trash [:svg props
              [:polyline {:points "3 6 5 6 21 6"}]
              [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]
      :sidebar [:svg props
                [:rect {:x "3" :y "3" :width "18" :height "18" :rx "2" :ry "2"}]
                [:line {:x1 "9" :y1 "3" :x2 "9" :y2 "21"}]]
      :x [:svg props
          [:line {:x1 "18" :y1 "6" :x2 "6" :y2 "18"}]
          [:line {:x1 "6" :y1 "6" :x2 "18" :y2 "18"}]]
      ;; Default fallback
      [:svg props [:circle {:cx "12" :cy "12" :r "4"}]])))

;; ── Page Menu (dropdown on hover) ────────────────────────────────────────────

(defn- PageMenu
  "Dropdown menu for page actions. Appears on hover."
  [{:keys [page-id title db on-intent visible?]}]
  (when visible?
    (let [descendants (q/descendants-of db page-id)]
      [:div.page-menu
       {:style {:position "absolute"
                :right "8px"
                :top "50%"
                :transform "translateY(-50%)"
                :z-index 10}}
       ;; Menu trigger (three dots)
       [:button.page-menu-trigger
        {:style {:display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :width "24px"
                 :height "24px"
                 :padding "0"
                 :background "var(--color-surface)"
                 :border "1px solid var(--color-border)"
                 :border-radius "var(--radius-sm)"
                 :cursor "pointer"
                 :color "var(--color-ink-faint)"
                 :transition "all 0.15s ease"
                 :box-shadow "0 1px 2px rgba(0,0,0,0.05)"}
         :on {:click (fn [e]
                       (.preventDefault e)
                       (.stopPropagation e)
                       ;; Delete with undo toast
                       (when on-intent
                         (on-intent {:type :delete-page :page-id page-id}))
                       (vs/show-notification!
                        (str "Deleted \"" (or title "Untitled") "\"")
                        {:type :success
                         :action {:label "Undo"
                                  :on-click (fn []
                                              (when on-intent
                                                (on-intent {:type :restore-page
                                                            :page-id page-id
                                                            :descendants descendants
                                                            :switch-to? true})))}}))}}
        (Icon {:name :trash :size 14})]])))

;; ── Page Item ────────────────────────────────────────────────────────────────

(defn- PageItem
  "Single page item in sidebar list."
  [{:keys [db page-id title is-current? is-journal? on-intent]}]
  (let [display-title (if (untitled? title) "Untitled" title)]
    [:div.sidebar-page-item
     {:class [(when is-current? "active")
              (when is-journal? "journal")]
      :on {:click (fn [e]
                    (.preventDefault e)
                    (when on-intent
                      (on-intent {:type :switch-page :page-id page-id})))}}
     ;; Icon
     [:span.page-icon
      (Icon {:name (if is-journal? :calendar :file) :size 14})]
     ;; Title
     [:span.page-title
      {:class (when (untitled? title) "untitled")}
      display-title]
     ;; Menu (shown on hover via CSS)
     (PageMenu {:page-id page-id
                :title title
                :db db
                :on-intent on-intent
                :visible? true})]))

;; ── Section Header ───────────────────────────────────────────────────────────

(defn- SectionHeader
  "Section header with optional action button."
  [{:keys [title action-label on-action]}]
  [:div.sidebar-section-header
   [:span.section-title title]
   (when action-label
     [:button.section-action
      {:on {:click (fn [e]
                     (.preventDefault e)
                     (when on-action (on-action)))}}
      (Icon {:name :plus :size 12})
      [:span action-label]])])

;; ── Storage Section ──────────────────────────────────────────────────────────

(defn- StorageSection
  "Folder connection status and picker."
  [{:keys [folder-name loading? on-pick-folder on-clear-folder]}]
  [:div.sidebar-storage
   (if folder-name
     ;; Connected state
     [:div.storage-connected
      [:span.storage-icon (Icon {:name :folder :size 14})]
      [:span.storage-name folder-name]
      [:button.storage-disconnect
       {:on {:click (fn [e]
                      (.preventDefault e)
                      (when on-clear-folder (on-clear-folder)))}
        :title "Disconnect folder"}
       (Icon {:name :x :size 12})]]
     ;; Disconnected state
     [:button.storage-picker
      {:disabled loading?
       :on {:click (fn [e]
                     (.preventDefault e)
                     (when on-pick-folder (on-pick-folder)))}}
      (Icon {:name :folder-open :size 16})
      [:span (if loading? "Loading..." "Open Folder")]])])

;; ── Main Sidebar ─────────────────────────────────────────────────────────────

(defn Sidebar
  "Left sidebar for page navigation.

   Features:
   - Folder connection status
   - Page list with journal detection
   - Hover-reveal actions (Logseq-style)
   - Toast undo for deletions"
  [{:keys [db on-intent on-pick-folder on-clear-folder storage-status]}]
  (let [all-pages (q/all-pages db)
        current-page-id (vs/current-page)
        folder-name (:folder-name storage-status)
        loading? (:loading? storage-status)
        ;; Separate journals from regular pages
        pages-with-meta (map (fn [pid]
                               (let [title (q/page-title db pid)]
                                 {:id pid
                                  :title title
                                  :journal? (journal-page? title)}))
                             all-pages)
        journals (filter :journal? pages-with-meta)
        regular-pages (remove :journal? pages-with-meta)]
    [:nav.sidebar {:aria-label "Page navigation"}

     ;; Storage section
     (StorageSection {:folder-name folder-name
                      :loading? loading?
                      :on-pick-folder on-pick-folder
                      :on-clear-folder on-clear-folder})

     ;; Pages section
     (SectionHeader {:title "Pages"
                     :action-label "New"
                     :on-action (fn []
                                  (let [title (js/prompt "Page title:")]
                                    (when (and title (not= title ""))
                                      (when on-intent
                                        (on-intent {:type :create-page :title title})))))})

     [:div.sidebar-page-list
      (if (seq all-pages)
        ;; Show journals first, then regular pages
        [:<>
         ;; Journal pages (if any)
         (when (seq journals)
           [:<>
            (for [{:keys [id title]} journals]
              ^{:key id}
              [PageItem {:db db
                         :page-id id
                         :title title
                         :is-current? (= id current-page-id)
                         :is-journal? true
                         :on-intent on-intent}])])
         ;; Regular pages
         (for [{:keys [id title]} regular-pages]
           ^{:key id}
           [PageItem {:db db
                      :page-id id
                      :title title
                      :is-current? (= id current-page-id)
                      :is-journal? false
                      :on-intent on-intent}])]
        ;; Empty state
        [:div.sidebar-empty
         [:p "No pages yet"]
         [:p.hint "Create your first page above"]])]]))
