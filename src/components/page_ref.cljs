(ns components.page-ref
  "Page reference component for rendering [[page]] links.

   Note: evo currently uses a single-document tree model.
   Page refs are rendered as styled links for future navigation support.")

(defn PageRef
  "Render a page reference link (Logseq-style orange).

   Props:
   - db: application database
   - page-name: Name of the page to reference
   - on-intent: Intent dispatch callback for navigation

   Behavior:
   - Renders [[page-name]] with orange Logseq-style color
   - Click navigates to page (or creates if new)"
  [{:keys [db page-name on-intent]}]
  [:a.page-ref
   {:href "#"
    :style {:color "#d9730d" ; Logseq orange
            :text-decoration "none"
            :font-weight "500"
            :cursor "pointer"}
    :title (str "Page: " page-name)
    :data-page-name page-name
    :on {:click (fn [e]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (when on-intent
                    (on-intent {:type :navigate-to-page
                                :page-name page-name})))}}
   "[[" page-name "]]"])
