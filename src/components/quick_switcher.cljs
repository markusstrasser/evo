(ns components.quick-switcher
  "Quick Switcher (Cmd+K) - Logseq-style global page search overlay.
   
   Uses native HTML <dialog> element for:
   - Automatic focus trapping (accessibility)
   - Native ::backdrop styling
   - Light-dismiss with closedby='any' (Chrome 134+)
   - Proper Escape key handling
   
   Features:
   - Full-screen modal overlay
   - Fuzzy search across all pages
   - Keyboard navigation (arrows, enter, escape)
   - Click to navigate to page"
  (:require [clojure.string :as str]
            [shell.view-state :as vs]
            [kernel.query :as q]
            [utils.dom :as dom]
            [utils.fuzzy-search :as fuzzy]))

;; ── Search Logic ──────────────────────────────────────────────────────────────

(defn- search-pages
  "Search pages by title using fuzzy matching.
   Returns list of {:page-id, :page-title, :score}

   Uses fuzzy/fuzzy-filter for consistent fuzzy search behavior.
   Score is inverted index for sorted results (1000, 999, 998, ...)."
  [db query]
  (let [all-page-ids (q/all-pages db)
        pages (map (fn [id]
                     {:page-id id
                      :page-title (q/page-title db id)})
                   all-page-ids)]
    (if (str/blank? query)
      ;; No query - return all pages sorted alphabetically with score 0
      (map #(assoc % :score 0) (sort-by :page-title pages))
      ;; Fuzzy search - use existing fuzzy-filter utility
      ;; Add descending scores for display consistency
      (->> (fuzzy/fuzzy-filter pages query :page-title ##Inf)
           (map-indexed (fn [idx page]
                          (assoc page :score (- 1000 idx))))))))

;; ── Highlight Helpers ─────────────────────────────────────────────────────────

(defn- ranges->hiccup
  "Convert highlight ranges to hiccup with <mark> tags.

   Takes original text and match ranges [[start end] ...],
   returns hiccup like [:span \"un\" [:mark \"mat\"] \"ched\"].

   Ranges must be non-overlapping and sorted by position."
  [text ranges]
  (if (empty? ranges)
    text
    (let [segments (loop [pos 0
                          remaining-ranges ranges
                          acc []]
                     (if (empty? remaining-ranges)
                       ;; Add final segment after last range
                       (if (< pos (count text))
                         (conj acc [:text (subs text pos)])
                         acc)
                       (let [[start end] (first remaining-ranges)
                             before (when (< pos start)
                                     [:text (subs text pos start)])
                             highlight [:mark (subs text start end)]]
                         (recur end
                                (rest remaining-ranges)
                                (cond-> acc
                                  before (conj before)
                                  true (conj highlight))))))]
      (into [:span]
            (map (fn [[tag content]]
                   (if (= tag :text)
                     content
                     [tag content]))
                 segments)))))

(defn- highlight-match
  "Highlight matching characters in text based on query.
   Returns hiccup with matched chars wrapped in <mark>.

   Uses fuzzy/highlight-match for consistent match detection."
  [query text]
  (if (str/blank? query)
    text
    (let [ranges (fuzzy/highlight-match text query)]
      (ranges->hiccup text ranges))))

;; ── Components ────────────────────────────────────────────────────────────────

(defn- handle-input-keydown
  "Handle keyboard navigation in quick switcher input.

   Supports:
   - ArrowDown/Up: Navigate through results
   - Enter: Select current result and navigate
   - Escape: Close dialog (browser native or manual)"
  [e result-count selected-result on-intent]
  (let [key-code (.-key e)]
    (case key-code
      "ArrowDown"
      (do (.preventDefault e)
          (vs/quick-switcher-navigate! :down result-count))

      "ArrowUp"
      (do (.preventDefault e)
          (vs/quick-switcher-navigate! :up result-count))

      "Enter"
      (do (.preventDefault e)
          (when selected-result
            (vs/quick-switcher-close!)
            (on-intent {:type :switch-page
                        :page-id (:page-id selected-result)})))

      ;; Escape handled by dialog closedby="any"
      ;; but add fallback for older browsers
      "Escape"
      (do (.preventDefault e)
          (-> e .-target .-closest
              (.call (.-target e) "dialog")
              (.close)))

      ;; Default - let it through
      nil)))

(defn- ResultItem
  "Single search result item."
  [{:keys [page-title selected? on-click]}]
  [:div.quick-switcher-item
   {:class (when selected? "selected")
    :replicant/on-render (when selected?
                           (fn [{:replicant/keys [node]}]
                             (dom/scroll-into-view! node)))
    :on {:click on-click}}
   [:span.quick-switcher-icon "📄"]
   [:span.quick-switcher-title page-title]])

(defn QuickSwitcher
  "Quick Switcher overlay component using native <dialog>.
   
   Props:
   - db: Application database
   - on-intent: Intent dispatch callback"
  [{:keys [db on-intent]}]
  (let [{:keys [query selected-idx]} (vs/quick-switcher)
        results (search-pages db query)
        result-count (count results)
        selected-result (when (and (pos? result-count)
                                   (< selected-idx result-count))
                          (nth results selected-idx))]
    [:dialog.quick-switcher
     {;; Light-dismiss: click outside or Esc closes (Chrome 134+)
      ;; Falls back to manual Esc handling for older browsers
      :closedby "any"
      :replicant/on-mount
      (fn [{:replicant/keys [node]}]
        ;; Show as modal (adds ::backdrop, traps focus)
        (.showModal node)
        ;; Focus the input after dialog opens
        (when-let [input (.querySelector node ".quick-switcher-input")]
          (.focus input)))
      ;; Handle close event (works for both closedby and manual close)
      :on {:close (fn [_e]
                    (vs/quick-switcher-close!))
           ;; Backdrop click fallback for browsers without closedby
           :click (fn [e]
                    (let [dialog (.-currentTarget e)
                          rect (.getBoundingClientRect dialog)]
                      ;; Click was outside dialog content (on backdrop)
                      (when (or (< (.-clientX e) (.-left rect))
                                (> (.-clientX e) (.-right rect))
                                (< (.-clientY e) (.-top rect))
                                (> (.-clientY e) (.-bottom rect)))
                        (.close dialog))))}}

     [:div.quick-switcher-content
      ;; Search input
      [:div.quick-switcher-input-wrapper
       [:input.quick-switcher-input
        {:type "text"
         :placeholder "Search pages..."
         :value (or query "")
         :on {:input (fn [e]
                       (vs/quick-switcher-set-query! (.. e -target -value)))
              :keydown (fn [e]
                         (handle-input-keydown e result-count selected-result on-intent))}}]]

      ;; Results list
      [:div.quick-switcher-results
       (if (empty? results)
         [:div.quick-switcher-empty
          (if (str/blank? query)
            "Type to search pages"
            "No matching pages")]

         (into [:div]
               (map-indexed
                (fn [idx {:keys [page-id page-title]}]
                  (ResultItem
                   {:page-title (highlight-match query page-title)
                    :selected? (= idx selected-idx)
                    :on-click (fn [_]
                                (vs/quick-switcher-close!)
                                (on-intent {:type :switch-page
                                            :page-id page-id}))}))
                results)))]]]))
