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
            [utils.fuzzy-search :as fuzzy]))

;; ── Search Logic ──────────────────────────────────────────────────────────────

(defn- search-pages
  "Search pages by title using fuzzy matching.
   Returns list of {:page-id, :page-title, :score}"
  [db query]
  (let [all-page-ids (q/all-pages db)]
    (if (str/blank? query)
      ;; No query - return all pages sorted alphabetically
      (->> all-page-ids
           (map (fn [page-id]
                  {:page-id page-id
                   :page-title (q/page-title db page-id)
                   :score 0}))
           (sort-by :page-title))
      ;; Fuzzy search
      (->> all-page-ids
           (map (fn [page-id]
                  (let [title (q/page-title db page-id)
                        score (fuzzy/match-score title query)]
                    {:page-id page-id
                     :page-title title
                     :score score})))
           (filter #(pos? (:score %)))
           (sort-by :score >)))))

;; ── Highlight Helpers ─────────────────────────────────────────────────────────

(defn- highlight-match
  "Highlight matching characters in text based on query.
   Returns hiccup with matched chars wrapped in <mark>."
  [query text]
  (if (str/blank? query)
    text
    (let [query-lower (str/lower-case query)
          text-lower (str/lower-case text)]
      (loop [result []
             text-idx 0
             query-idx 0]
        (if (>= text-idx (count text))
          (into [:span] result)
          (let [text-char (nth text text-idx)
                matches? (and (< query-idx (count query-lower))
                              (= (nth text-lower text-idx)
                                 (nth query-lower query-idx)))]
            (recur (conj result (if matches?
                                  [:mark (str text-char)]
                                  (str text-char)))
                   (inc text-idx)
                   (if matches? (inc query-idx) query-idx))))))))

;; ── Components ────────────────────────────────────────────────────────────────

(defn- ResultItem
  "Single search result item."
  [{:keys [page-title selected? on-click]}]
  [:div.quick-switcher-item
   {:class (when selected? "selected")
    :replicant/on-render (when selected?
                           (fn [{:replicant/keys [node]}]
                             (.scrollIntoView node #js {:block "nearest"})))
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
                             nil)))}}]]

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
