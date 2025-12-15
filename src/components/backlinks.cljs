(ns components.backlinks
  "Backlinks panel showing blocks that reference the current page.
   
   Logseq calls this 'Linked References' - shows all blocks containing
   [[Current Page]] references, grouped by source page.
   
   Styling: Editorial design inspired by publishing/Acknowledgements component."
  (:require [plugins.backlinks-index :as backlinks]
            [clojure.string :as str]
            [components.page-ref :as page-ref]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- parse-text-with-refs
  "Parse text and return hiccup with page refs as clickable links.
   Text like 'See [[Projects]] and [[Tasks]]' becomes mixed content
   with PageRef components for the links."
  [text on-intent]
  (let [;; Non-capturing pattern for split (to avoid CLJS including captured groups)
        split-pattern #"\[\[[^\]]+\]\]"
        ;; Capturing pattern to extract page names
        match-pattern #"\[\[([^\]]+)\]\]"
        parts (str/split text split-pattern)
        refs (->> (re-seq match-pattern text)
                  (map second))]
    (if (empty? refs)
      ;; No refs, just return text
      text
      ;; Interleave text parts with page ref components
      (into [:span]
            (loop [result []
                   remaining-parts parts
                   remaining-refs refs]
              (if (empty? remaining-parts)
                result
                (let [text-part (first remaining-parts)
                      ref-name (first remaining-refs)]
                  (recur (cond-> result
                           ;; Add text part if non-empty
                           (seq text-part)
                           (conj text-part)
                           ;; Add page ref if we have one
                           ref-name
                           (conj (page-ref/PageRef {:page-name ref-name
                                                    :on-intent on-intent})))
                         (rest remaining-parts)
                         (rest remaining-refs)))))))))

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
   
   Props:
   - db: Application database
   - page-title: Title of the current page
   - on-intent: Intent dispatch callback"
  [{:keys [db page-title on-intent]}]
  (let [backlinks (backlinks/get-backlinks db page-title)
        grouped (group-by-page backlinks)
        backlink-count (count backlinks)]
    [:aside.backlinks-panel {:aria-label "Linked References"}

     ;; Header with ornament and count
     [:div.backlinks-header
      [:span.backlinks-ornament "❧"]
      [:h4.backlinks-title "Linked References"]
      (when (pos? backlink-count)
        [:span.backlinks-count backlink-count])]

     ;; Content
     [:div.backlinks-content
      (if (empty? backlinks)
        [:p.backlinks-empty "No references to this page yet."]

        (into [:<>]
              (map (fn [group]
                     (BacklinkGroup {:page-title (:page-title group)
                                     :blocks (:blocks group)
                                     :on-intent on-intent}))
                   grouped)))]]))
