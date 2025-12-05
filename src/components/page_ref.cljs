(ns components.page-ref
  "Page reference component for rendering [[page]] links.

   Note: evo currently uses a single-document tree model.
   Page refs are rendered as styled links for future navigation support.")

(defn PageRef
  "Render a page reference link.

   Props:
   - db: application database
   - page-name: Name of the page to reference
   - on-intent: Intent dispatch callback (for future navigation)

   Current Behavior:
   Since evo doesn't have multi-page support yet, this renders a styled
   link that:
   - Shows the page name with distinct styling
   - Could be clicked in future for navigation
   - Tracks page refs in the refs plugin

   Future:
   - Navigate to page on click
   - Show backlinks
   - Auto-create pages"
  [{:keys [db page-name on-intent]}]
  [:a.page-ref
   {:href "#"
    :style {:color "#228be6"
            :text-decoration "none"
            :font-weight "500"
            :padding "2px 4px"
            :border-radius "3px"
            :background-color "#e7f5ff"
            :cursor "pointer"}
    :title (str "Page reference: " page-name)
    :data-page-name page-name
    :on {:click (fn [e]
                  (.preventDefault e)
                  (when on-intent
                    (on-intent {:type :navigate-to-page
                                :page-name page-name})))
         :mouseenter (fn [e]
                       ;; Future: show page preview on hover
                       nil)}}
   "[[" page-name "]]"])
