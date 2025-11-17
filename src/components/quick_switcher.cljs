(ns components.quick-switcher
  "Quick switcher overlay component."
  (:require [kernel.constants :as const]))

(defn QuickSwitcher
  "Quick switcher overlay for searching pages and blocks.

   Displays a centered modal with search input and filtered results.
   Keyboard navigation: arrows move selection, Enter opens, Esc closes."
  [{:keys [db on-intent]}]
  (when-let [switcher (get-in db [:nodes const/session-ui-id :props :quick-switcher])]
    (let [{:keys [query results selected-idx]} switcher]
      [:div.quick-switcher-overlay
       {:style {:position "fixed"
                :top "0"
                :left "0"
                :width "100vw"
                :height "100vh"
                :background-color "rgba(0, 0, 0, 0.5)"
                :z-index "9999"
                :display "flex"
                :align-items "flex-start"
                :justify-content "center"
                :padding-top "15vh"}
        :on-click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (on-intent {:type :quick-switcher/close})))}

       [:div.quick-switcher-modal
        {:style {:background-color "white"
                 :border-radius "8px"
                 :box-shadow "0 10px 40px rgba(0, 0, 0, 0.2)"
                 :width "600px"
                 :max-height "500px"
                 :display "flex"
                 :flex-direction "column"}
         :on-click (fn [e] (.stopPropagation e))}

        ;; Search input
        [:div.quick-switcher-search
         {:style {:padding "16px"
                  :border-bottom "1px solid #e5e7eb"}}
         [:input
          {:type "text"
           :placeholder "Search pages and blocks..."
           :auto-focus true
           :value query
           :style {:width "100%"
                   :padding "12px"
                   :font-size "16px"
                   :border "2px solid #3b82f6"
                   :border-radius "6px"
                   :outline "none"}
           :on-input (fn [e]
                       (let [new-query (.. e -target -value)]
                         (on-intent {:type :quick-switcher/update-query
                                     :query new-query})))
           :on-key-down (fn [e]
                          (let [k (.-key e)]
                            (cond
                              (or (= k "ArrowDown") (and (.-ctrlKey e) (= k "n")))
                              (do (.preventDefault e)
                                  (on-intent {:type :quick-switcher/next}))

                              (or (= k "ArrowUp") (and (.-ctrlKey e) (= k "p")))
                              (do (.preventDefault e)
                                  (on-intent {:type :quick-switcher/prev}))

                              (= k "Enter")
                              (do (.preventDefault e)
                                  (on-intent {:type :quick-switcher/select}))

                              (= k "Escape")
                              (do (.preventDefault e)
                                  (on-intent {:type :quick-switcher/close})))))}]]

        ;; Results list
        [:div.quick-switcher-results
         {:style {:overflow-y "auto"
                  :max-height "400px"}}
         (if (empty? results)
           [:div.no-results
            {:style {:padding "32px"
                     :text-align "center"
                     :color "#9ca3af"}}
            "No results found"]

           (for [[idx result] (map-indexed vector results)]
             (let [selected? (= idx selected-idx)
                   {:keys [type title preview]} result]
               [:div.result-item
                {:key (str "result-" idx)
                 :style {:padding "12px 16px"
                         :cursor "pointer"
                         :background-color (if selected? "#eff6ff" "white")
                         :border-left (if selected? "3px solid #3b82f6" "3px solid transparent")
                         :transition "background-color 0.15s"}
                 :on-mouse-enter (fn [_]
                                   (on-intent {:type :quick-switcher/next}))
                 :on-click (fn [_]
                             (on-intent {:type :quick-switcher/select}))}

                ;; Type icon
                [:span.result-type-icon
                 {:style {:margin-right "8px"
                          :opacity "0.6"}}
                 (if (= type :page) "📄" "📦")]

                ;; Title or preview
                [:span.result-content
                 {:style {:font-size "14px"
                          :color (if selected? "#1e40af" "#374151")
                          :font-weight (if selected? "600" "400")}}
                 (or title preview "Untitled")]])))]]])))
