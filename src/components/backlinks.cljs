(ns components.backlinks
  "Backlinks panel showing blocks that reference the current page.
   
   Logseq calls this 'Linked References' - shows all blocks containing
   [[Current Page]] references, grouped by source page."
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
   {:style {:padding "8px 12px"
            :margin "4px 0"
            :background "var(--color-surface, #f5f5f5)"
            :border-radius "4px"
            :font-size "13px"
            :line-height "1.5"}
    :replicant/key block-id}
   ;; Parse text and render [[Page]] refs as clickable links
   (parse-text-with-refs block-text on-intent)])

(defn- BacklinkGroup
  "Group of backlinks from a single page."
  [{:keys [page-title blocks on-intent]}]
  [:div.backlink-group
   {:style {:margin-bottom "16px"}}

   ;; Page header (clickable to navigate)
   [:div.backlink-page-header
    {:style {:font-weight "600"
             :font-size "14px"
             :color "var(--color-text, #333)"
             :margin-bottom "8px"
             :cursor "pointer"}
     :on {:click (fn [e]
                   (.preventDefault e)
                   (when on-intent
                     (on-intent {:type :navigate-to-page
                                 :page-name page-title})))}}
    "📄 " page-title]

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
    [:div.backlinks-panel
     {:style {:margin-top "40px"
              :padding-top "20px"
              :border-top "1px solid var(--color-border, #e5e5e5)"}}

     ;; Header with count
     [:div.backlinks-header
      {:style {:display "flex"
               :align-items "center"
               :gap "8px"
               :margin-bottom "16px"}}
      [:h4 {:style {:margin "0"
                    :font-size "14px"
                    :font-weight "600"
                    :color "var(--color-text-muted, #666)"}}
       "Linked References"]
      [:span.backlinks-count
       {:style {:background "var(--color-surface, #f0f0f0)"
                :padding "2px 8px"
                :border-radius "10px"
                :font-size "12px"
                :color "var(--color-text-muted, #666)"}}
       backlink-count]]

     ;; Content
     (if (empty? backlinks)
       [:div.backlinks-empty
        {:style {:padding "20px"
                 :text-align "center"
                 :color "var(--color-text-muted, #999)"
                 :font-size "13px"}}
        "No references to this page yet"]

       (into [:div.backlinks-content]
             (map (fn [group]
                    (BacklinkGroup {:page-title (:page-title group)
                                    :blocks (:blocks group)
                                    :on-intent on-intent}))
                  grouped)))]))
