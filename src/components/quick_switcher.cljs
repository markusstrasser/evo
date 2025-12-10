(ns components.quick-switcher
  "Quick Switcher (Cmd+K) - Logseq-style global page search overlay.
   
   Features:
   - Full-screen modal overlay
   - Fuzzy search across all pages
   - Keyboard navigation (arrows, enter, escape)
   - Click to navigate to page"
  (:require [clojure.string :as str]
            [shell.view-state :as vs]
            [plugins.pages :as pages]
            [utils.fuzzy-search :as fuzzy]))

;; ── Search Logic ──────────────────────────────────────────────────────────────

(defn- search-pages
  "Search pages by title using fuzzy matching.
   Returns list of {:page-id, :page-title, :score}"
  [db query]
  (let [all-page-ids (pages/all-pages db)]
    (if (str/blank? query)
      ;; No query - return all pages sorted alphabetically
      (->> all-page-ids
           (map (fn [page-id]
                  {:page-id page-id
                   :page-title (pages/page-title db page-id)
                   :score 0}))
           (sort-by :page-title))
      ;; Fuzzy search
      (->> all-page-ids
           (map (fn [page-id]
                  (let [title (pages/page-title db page-id)
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
                                  [:mark {:style {:background "#fef08a"
                                                  :color "inherit"
                                                  :padding "0 1px"
                                                  :border-radius "2px"}}
                                   (str text-char)]
                                  (str text-char)))
                   (inc text-idx)
                   (if matches? (inc query-idx) query-idx))))))))

;; ── Components ────────────────────────────────────────────────────────────────

(defn- ResultItem
  "Single search result item."
  [{:keys [page-title selected? on-click]}]
  [:div.quick-switcher-item
   {:style {:padding "10px 16px"
            :cursor "pointer"
            :display "flex"
            :align-items "center"
            :gap "10px"
            :background (if selected? "#e0e7ff" "transparent")
            :border-radius "6px"
            :margin "2px 0"}
    :on {:click on-click
         :mouseenter (fn [_] nil)}} ; Could highlight on hover
   [:span {:style {:font-size "16px"}} "📄"]
   [:span {:style {:font-size "14px"}} page-title]])

(defn QuickSwitcher
  "Quick Switcher overlay component.
   
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
    [:div.quick-switcher-overlay
     {:style {:position "fixed"
              :top 0
              :left 0
              :right 0
              :bottom 0
              :background "rgba(0, 0, 0, 0.5)"
              :display "flex"
              :align-items "flex-start"
              :justify-content "center"
              :padding-top "15vh"
              :z-index 1000}
      :on {:click (fn [e]
                    ;; Close when clicking backdrop (not the modal)
                    (when (= (.-target e) (.-currentTarget e))
                      (vs/quick-switcher-close!)))}}

     [:div.quick-switcher-modal
      {:style {:background "white"
               :border-radius "12px"
               :box-shadow "0 25px 50px -12px rgba(0, 0, 0, 0.25)"
               :width "100%"
               :max-width "560px"
               :max-height "70vh"
               :display "flex"
               :flex-direction "column"
               :overflow "hidden"}}

      ;; Search input
      [:div.quick-switcher-input-wrapper
       {:style {:padding "16px"
                :border-bottom "1px solid #e5e7eb"}}
       [:input.quick-switcher-input
        {:type "text"
         :placeholder "Search pages..."
         :value (or query "")
         :style {:width "100%"
                 :font-size "16px"
                 :padding "12px 16px"
                 :border "2px solid #e5e7eb"
                 :border-radius "8px"
                 :outline "none"
                 :transition "border-color 0.15s"}
         :replicant/on-mount
         (fn [{:replicant/keys [node]}]
           ;; Focus input when modal opens
           (.focus node))
         :on {:input (fn [e]
                       (vs/quick-switcher-set-query! (.. e -target -value)))
              :keydown (fn [e]
                         (let [key-code (.-key e)]
                           (case key-code
                             "Escape"
                             (do (.preventDefault e)
                                 (vs/quick-switcher-close!))

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

                             ;; Default - let it through
                             nil)))}}]]

      ;; Results list
      [:div.quick-switcher-results
       {:style {:padding "8px"
                :overflow-y "auto"
                :flex "1"}}

       (if (empty? results)
         [:div {:style {:padding "20px"
                        :text-align "center"
                        :color "#9ca3af"}}
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

