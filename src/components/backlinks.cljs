(ns components.backlinks
  "Backlinks panel showing blocks that reference the current page.
   
   Logseq calls this 'Linked References' - shows all blocks containing
   [[Current Page]] references, grouped by source page.
   
   Styling: Editorial design inspired by publishing/Acknowledgements component."
  (:require [plugins.backlinks-index :as backlinks]
            [parser.page-refs :as page-refs]
            [components.page-ref :as page-ref]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- parse-text-with-refs
  "Parse text and return hiccup with page refs as clickable links.
   Text like 'See [[Projects]] and [[Tasks]]' becomes mixed content
   with PageRef components for the links."
  [text on-intent]
  (let [segments (page-refs/split-with-refs text)]
    (if (not-any? #(= :page-ref (:type %)) segments)
      text
      (into [:span]
            (keep (fn [{:keys [type value page]}]
                    (case type
                      :text value
                      :page-ref (page-ref/PageRef {:page-name page
                                                   :on-intent on-intent}))))
            segments))))

(defn- group-by-page
  "Group backlinks by their source page."
  [backlinks]
  (->> backlinks
       (group-by :page-id)
       (map (fn [[page-id links]]
              {:page-id page-id
               :page-title (:page-title (first links))
               :blocks links}))
       (sort-by :page-title)))

;; ── Components ────────────────────────────────────────────────────────────────

(defn- BacklinkBlock
  "Single backlink block showing the referencing text with clickable page refs."
  [{:keys [block-text block-id on-intent]}]
  [:div.backlink-block
   {:replicant/key block-id}
   (parse-text-with-refs block-text on-intent)])

(defn- BacklinkGroup
  "Group of backlinks from a single page."
  [{:keys [page-title blocks on-intent]}]
  [:div.backlink-group

   ;; Page header (clickable to navigate)
   [:div.backlink-page-header
    {:on {:click (fn [e]
                   (.preventDefault e)
                   (when on-intent
                     (on-intent {:type :navigate-to-page
                                 :page-name page-title})))}}
    page-title]

   ;; Blocks from this page
   (into [:div.backlink-blocks]
         (map (fn [block]
                (BacklinkBlock {:block-text (:block-text block)
                                :block-id (:block-id block)
                                :on-intent on-intent}))
              blocks))])

;; ── Main Panel ────────────────────────────────────────────────────────────────

(defn BacklinksPanel
  "Panel showing all backlinks to the current page.
   Returns nil if no backlinks exist (panel hidden entirely).
   
   Props:
   - db: Application database
   - page-title: Title of the current page
   - on-intent: Intent dispatch callback"
  [{:keys [db page-title on-intent]}]
  (let [backlinks (backlinks/get-backlinks db page-title)
        grouped (group-by-page backlinks)
        backlink-count (count backlinks)]
    ;; Only render if there are actual backlinks
    (when (pos? backlink-count)
      [:aside.backlinks-panel {:aria-label "Linked References"}

       ;; Header with ornament and count
       [:div.backlinks-header
        [:span.backlinks-ornament "❧"]
        [:h4.backlinks-title "Linked References"]
        [:span.backlinks-count backlink-count]]

       ;; Content - grouped by source page
       [:div.backlinks-content
        (for [group grouped]
          ^{:key (:page-id group)}
          (BacklinkGroup {:page-title (:page-title group)
                          :blocks (:blocks group)
                          :on-intent on-intent}))]])))
