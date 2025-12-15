(ns components.sidebar
  "Sidebar component for page navigation.

   Design: Logseq-inspired left sidebar with:
   - Favorites (star icon affordance)
   - Recent pages (auto-populated on visits)
   - Journals (date-formatted pages)
   - All Pages (remaining valid pages)

   Invalid pages (Untitled, blank) are filtered from display."
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

(defn- parse-journal-date
  "Parse journal title to sortable ISO date (YYYY-MM-DD)."
  [title]
  (when title
    (cond
      ;; ISO format: 2025-12-14
      (re-matches #"\d{4}-\d{2}-\d{2}" title)
      title

      ;; Human format: Dec 14th, 2025 -> 2025-12-14
      (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}" title)
      (let [months {"Jan" "01" "Feb" "02" "Mar" "03" "Apr" "04"
                    "May" "05" "Jun" "06" "Jul" "07" "Aug" "08"
                    "Sep" "09" "Oct" "10" "Nov" "11" "Dec" "12"}
            [_ mon day _ year] (re-matches #"([A-Z][a-z]{2}) (\d{1,2})(st|nd|rd|th), (\d{4})" title)]
        (when (and mon day year)
          (str year "-" (months mon) "-" (when (< (count day) 2) "0") day)))

      :else nil)))

(defn- today-iso
  "Get today's date in ISO format."
  []
  (let [now (js/Date.)
        y (.getFullYear now)
        m (inc (.getMonth now))
        d (.getDate now)]
    (str y "-" (when (< m 10) "0") m "-" (when (< d 10) "0") d)))

(defn- has-content?
  "Check if a journal page has any non-empty blocks."
  [db page-id]
  (let [children (q/children db page-id)]
    (some (fn [bid]
            (let [text (q/block-text db bid)]
              (and text (not (str/blank? text)))))
          children)))

(defn- valid-page?
  "Check if page has a valid, displayable title.
   Filters out Untitled and blank pages."
  [title]
  (and (some? title)
       (not (str/blank? title))
       (not= title "Untitled")))

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
      :trash [:svg props
              [:polyline {:points "3 6 5 6 21 6"}]
              [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]
      :x [:svg props
          [:line {:x1 "18" :y1 "6" :x2 "6" :y2 "18"}]
          [:line {:x1 "6" :y1 "6" :x2 "18" :y2 "18"}]]
      :star [:svg props
             [:polygon {:points "12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"}]]
      :star-filled [:svg (assoc props :fill "currentColor")
                    [:polygon {:points "12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"}]]
      :clock [:svg props
              [:circle {:cx "12" :cy "12" :r "10"}]
              [:polyline {:points "12 6 12 12 16 14"}]]
      :chevron-down [:svg props
                     [:polyline {:points "6 9 12 15 18 9"}]]
      :chevron-right [:svg props
                      [:polyline {:points "9 18 15 12 9 6"}]]
      ;; Default fallback
      [:svg props [:circle {:cx "12" :cy "12" :r "4"}]])))

;; ── Page Actions ─────────────────────────────────────────────────────────────

(defn- StarButton
  "Star icon button for favoriting a page."
  [{:keys [page-id is-favorite?]}]
  [:button.star-button
   {:class (when is-favorite? "active")
    :title (if is-favorite? "Remove from favorites" "Add to favorites")
    :on {:click (fn [e]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (vs/toggle-favorite! page-id))}}
   (Icon {:name (if is-favorite? :star-filled :star) :size 12})])

(defn- DeleteButton
  "Delete button for removing a page."
  [{:keys [page-id title db on-intent]}]
  (let [descendants (q/descendants-of db page-id)]
    [:button.delete-button
     {:title "Delete page"
      :on {:click (fn [e]
                    (.preventDefault e)
                    (.stopPropagation e)
                    (when on-intent
                      (on-intent {:type :delete-page :page-id page-id}))
                    ;; Remove from favorites if favorited
                    (vs/remove-favorite! page-id)
                    (vs/show-notification!
                     (str "Deleted \"" (or title "page") "\"")
                     {:type :success
                      :action {:label "Undo"
                               :on-click (fn []
                                           (when on-intent
                                             (on-intent {:type :restore-page
                                                         :page-id page-id
                                                         :descendants descendants
                                                         :switch-to? true})))}}))}}
     (Icon {:name :trash :size 12})]))

;; ── Page Item ────────────────────────────────────────────────────────────────

(defn- PageItem
  "Single page item in sidebar list."
  [{:keys [db page-id title is-current? is-journal? is-favorite? on-intent show-star?]}]
  [:div.sidebar-page-item
   {:class [(when is-current? "active")
            (when is-journal? "journal")]
    :on {:click (fn [e]
                  (.preventDefault e)
                  ;; Track in recents when clicking
                  (vs/add-to-recents! page-id)
                  (when on-intent
                    (on-intent {:type :switch-page :page-id page-id})))}}
   ;; Title (no icon - cleaner UI)
   [:span.page-title title]
   ;; Actions (shown on hover via CSS)
   [:span.page-actions
    (when show-star?
      (StarButton {:page-id page-id :is-favorite? is-favorite?}))
    (DeleteButton {:page-id page-id :title title :db db :on-intent on-intent})]])

;; ── Collapsible Section ──────────────────────────────────────────────────────

(defn- CollapsibleSection
  "Collapsible section with header, count badge, and optional action."
  [{:keys [title count collapsed? on-toggle on-action action-label children]}]
  [:div.sidebar-section {:class (when collapsed? "collapsed")}
   ;; Header (no icons - cleaner UI)
   [:div.sidebar-section-header
    {:on {:click (fn [e]
                   (.preventDefault e)
                   (when on-toggle (on-toggle)))}}
    [:span.section-chevron
     (Icon {:name (if collapsed? :chevron-right :chevron-down) :size 12})]
    [:span.section-title title]
    (when (and count (pos? count))
      [:span.section-count count])
    (when action-label
      [:button.section-action
       {:on {:click (fn [e]
                      (.preventDefault e)
                      (.stopPropagation e)
                      (when on-action (on-action)))}}
       (Icon {:name :plus :size 12})])]
   ;; Content (hidden when collapsed)
   (when-not collapsed?
     [:div.sidebar-section-content children])])

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

;; ── Navigation Item ──────────────────────────────────────────────────────────

(defn- NavItem
  "Single navigation item (like Journals link in Logseq)."
  [{:keys [icon label on-click]}]
  [:div.sidebar-nav-item
   {:on {:click (fn [e]
                  (.preventDefault e)
                  (when on-click (on-click)))}}
   [:span.nav-icon (Icon {:name icon :size 16})]
   [:span.nav-label label]])

;; ── Main Sidebar ─────────────────────────────────────────────────────────────

(defn Sidebar
  "Left sidebar for page navigation (Logseq-style).

   Structure (matches Logseq):
   - Navigation links: Journals, All Pages
   - Favorites: User-starred pages (star icon affordance)
   - Recents: Auto-populated on page visits

   LOGSEQ PARITY:
   - All Pages is a nav link (opens page listing view), NOT inline list
   - Recents exclude journal pages
   - Invalid pages (Untitled, blank) are filtered"
  [{:keys [db on-intent on-pick-folder on-clear-folder storage-status]}]
  (let [all-pages (q/all-pages db)
        current-page-id (vs/current-page)
        folder-name (:folder-name storage-status)
        loading? (:loading? storage-status)
        favorites-set (vs/favorites)
        recents-list (vs/recents)
        today (today-iso)
        sidebar-width (vs/sidebar-width)

        ;; Build page metadata, filtering invalid pages
        pages-with-meta (->> all-pages
                             (map (fn [pid]
                                    (let [title (q/page-title db pid)
                                          date (parse-journal-date title)]
                                      {:id pid
                                       :title title
                                       :journal? (journal-page? title)
                                       :is-today? (= date today)
                                       :has-content? (has-content? db pid)
                                       :favorite? (contains? favorites-set pid)
                                       :valid? (valid-page? title)})))
                             (filter :valid?))

        ;; Separate journals from regular pages
        all-journal-pages (filter :journal? pages-with-meta)
        regular-pages (remove :journal? pages-with-meta)

        ;; Filter journals: only show today OR those with content (Logseq parity)
        visible-journal-pages (filter #(or (:is-today? %) (:has-content? %)) all-journal-pages)

        ;; Group regular pages by type (LOGSEQ: recents exclude journals)
        favorites (->> regular-pages
                       (filter :favorite?)
                       (sort-by :title))
        recents (->> recents-list
                     (keep (fn [pid]
                             (some #(when (= (:id %) pid) %) regular-pages)))
                     (remove :favorite?) ; Don't duplicate favorites
                     (take 10))

        ;; Resize handle event handlers
        on-resize-start
        (fn [e]
          (.preventDefault e)
          (let [start-x (.-clientX e)
                start-width sidebar-width
                ^js handle (.-currentTarget e)
                body js/document.body
                root js/document.documentElement
                ;; Use volatile to allow on-up to reference itself
                !on-move (volatile! nil)
                !on-up (volatile! nil)]

            (vreset! !on-move
                     (fn [move-e]
                       (let [delta (- (.-clientX move-e) start-x)
                             new-width (+ start-width delta)
                             clamped-width (vs/set-sidebar-width! new-width)]
                         ;; Update CSS variable on document root for layout coordination
                         (.setProperty (.-style root)
                                       "--sidebar-width" (str clamped-width "px")))))

            (vreset! !on-up
                     (fn [_]
                       (.classList.remove body "sidebar-resizing")
                       (.classList.remove ^js handle "dragging")
                       (.removeEventListener js/document "mousemove" @!on-move)
                       (.removeEventListener js/document "mouseup" @!on-up)))

            (.classList.add body "sidebar-resizing")
            (.classList.add ^js handle "dragging")
            (.addEventListener js/document "mousemove" @!on-move)
            (.addEventListener js/document "mouseup" @!on-up)))]

    [:nav.sidebar {:aria-label "Page navigation"
                   :replicant/on-mount
                   (fn [_]
                     ;; Set initial sidebar width on document root for layout coordination
                     (.setProperty (.-style js/document.documentElement)
                                   "--sidebar-width" (str sidebar-width "px")))}

     ;; Resize handle
     [:div.sidebar-resize-handle
      {:on {:mousedown on-resize-start}
       :title "Drag to resize"}]

     ;; Storage section
     (StorageSection {:folder-name folder-name
                      :loading? loading?
                      :on-pick-folder on-pick-folder
                      :on-clear-folder on-clear-folder})

     ;; Navigation section (Logseq-style)
     [:div.sidebar-nav
      ;; Journals link - opens journals view
      [:div.sidebar-nav-item
       {:class (when (vs/journals-view?) "active")
        :on {:click (fn [e]
                      (.preventDefault e)
                      (on-intent {:type :open-journals-view}))}}
       [:span.nav-icon (Icon {:name :calendar :size 16})]
       [:span.nav-label "Journals"]
       ;; Count shows only visible journals (with content or today)
       (when (seq visible-journal-pages)
         [:span.nav-count (count visible-journal-pages)])]

      ;; All Pages link - opens page listing (like Logseq)
      [:div.sidebar-nav-item
       {:class (when (and (not (vs/journals-view?))
                          (nil? current-page-id))
                 "active")
        :on {:click (fn [e]
                      (.preventDefault e)
                      (on-intent {:type :open-all-pages-view}))}}
       [:span.nav-icon (Icon {:name :file :size 16})]
       [:span.nav-label "All Pages"]
       (when (seq regular-pages)
         [:span.nav-count (count regular-pages)])]]

     ;; Favorites section
     (when (seq favorites)
       (CollapsibleSection
        {:title "Favorites"
         :count (count favorites)
         :collapsed? false
         :children
         (into [:div.page-list]
               (for [{:keys [id title]} favorites]
                 ^{:key id}
                 (PageItem {:db db
                            :page-id id
                            :title title
                            :is-current? (= id current-page-id)
                            :is-journal? false
                            :is-favorite? true
                            :show-star? true
                            :on-intent on-intent})))}))

     ;; Recents section (LOGSEQ: excludes journals, excludes favorites)
     (when (seq recents)
       (CollapsibleSection
        {:title "Recents"
         :count (count recents)
         :collapsed? false
         :children
         (into [:div.page-list]
               (for [{:keys [id title favorite?]} recents]
                 ^{:key id}
                 (PageItem {:db db
                            :page-id id
                            :title title
                            :is-current? (= id current-page-id)
                            :is-journal? false
                            :is-favorite? favorite?
                            :show-star? true
                            :on-intent on-intent})))}))]))
