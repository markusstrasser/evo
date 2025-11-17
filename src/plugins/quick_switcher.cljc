(ns plugins.quick-switcher
  "Quick switcher (Cmd+K / Cmd+P) plugin.

   Provides Logseq-style overlay search for pages and blocks:
   - Cmd+K or Cmd+P opens switcher
   - Real-time filtering as you type
   - Arrow keys navigate results
   - Enter opens selection
   - Escape closes without side effects"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Search & Filter
;; ══════════════════════════════════════════════════════════════════════════════

(defn search-pages
  "Search all pages by title (case-insensitive substring match)."
  [db query]
  (let [query-lower (str/lower-case query)
        pages (->> (get-in db [:nodes])
                   (filter (fn [[_id node]]
                             (= (:type node) :page)))
                   (map (fn [[id node]]
                          {:id id
                           :type :page
                           :title (get-in node [:props :title])
                           :preview nil})))]
    (if (str/blank? query)
      pages
      (filter (fn [{:keys [title]}]
                (str/includes? (str/lower-case title) query-lower))
              pages))))

(defn search-blocks
  "Search all blocks by text content (case-insensitive substring match)."
  [db query]
  (let [query-lower (str/lower-case query)
        blocks (->> (get-in db [:nodes])
                    (filter (fn [[_id node]]
                              (= (:type node) :block)))
                    (map (fn [[id node]]
                           {:id id
                            :type :block
                            :title nil
                            :preview (get-in node [:props :text])})))]
    (if (str/blank? query)
      [] ;; Don't show all blocks when query is empty
      (filter (fn [{:keys [preview]}]
                (str/includes? (str/lower-case preview) query-lower))
              blocks))))

(defn search-all
  "Search both pages and blocks, return combined results (pages first)."
  [db query]
  (let [pages (search-pages db query)
        blocks (if (str/blank? query)
                 []
                 (take 10 (search-blocks db query)))]
    (concat pages blocks)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Intent Handlers
;; ══════════════════════════════════════════════════════════════════════════════

(intent/register-intent! :quick-switcher/open
                         {:doc "Open quick switcher overlay (Cmd+K / Cmd+P)."
                          :spec [:map [:type [:= :quick-switcher/open]]]
                          :handler
                          (fn [db _]
                            (let [results (search-all db "")]
                              [{:op :update-node
                                :id const/session-ui-id
                                :props {:quick-switcher {:query ""
                                                         :results results
                                                         :selected-idx 0}}}]))})

(intent/register-intent! :quick-switcher/update-query
                         {:doc "Update quick switcher search query and filter results."
                          :spec [:map
                                 [:type [:= :quick-switcher/update-query]]
                                 [:query :string]]
                          :handler
                          (fn [db {:keys [query]}]
                            (when (get-in db [:nodes const/session-ui-id :props :quick-switcher])
                              (let [results (search-all db query)]
                                [{:op :update-node
                                  :id const/session-ui-id
                                  :props {:quick-switcher {:query query
                                                           :results results
                                                           :selected-idx 0}}}])))})

(intent/register-intent! :quick-switcher/next
                         {:doc "Select next result (Down / Ctrl+N)."
                          :spec [:map [:type [:= :quick-switcher/next]]]
                          :handler
                          (fn [db _]
                            (when-let [switcher (get-in db [:nodes const/session-ui-id :props :quick-switcher])]
                              (let [idx (:selected-idx switcher)
                                    total (count (:results switcher))
                                    next-idx (if (< idx (dec total)) (inc idx) 0)]
                                [{:op :update-node
                                  :id const/session-ui-id
                                  :props {:quick-switcher (assoc switcher :selected-idx next-idx)}}])))})

(intent/register-intent! :quick-switcher/prev
                         {:doc "Select previous result (Up / Ctrl+P)."
                          :spec [:map [:type [:= :quick-switcher/prev]]]
                          :handler
                          (fn [db _]
                            (when-let [switcher (get-in db [:nodes const/session-ui-id :props :quick-switcher])]
                              (let [idx (:selected-idx switcher)
                                    total (count (:results switcher))
                                    prev-idx (if (pos? idx) (dec idx) (dec total))]
                                [{:op :update-node
                                  :id const/session-ui-id
                                  :props {:quick-switcher (assoc switcher :selected-idx prev-idx)}}])))})

(intent/register-intent! :quick-switcher/select
                         {:doc "Open selected result and close switcher (Enter)."
                          :spec [:map [:type [:= :quick-switcher/select]]]
                          :handler
                          (fn [db _]
                            (when-let [switcher (get-in db [:nodes const/session-ui-id :props :quick-switcher])]
                              (let [{:keys [results selected-idx]} switcher
                                    selected (nth results selected-idx nil)]
                                (when selected
                                  (case (:type selected)
                                    ;; For pages, navigate to that page
                                    :page
                                    [{:op :update-node
                                      :id const/session-ui-id
                                      :props {:quick-switcher nil
                                              :current-page (:id selected)}}]

                                    ;; For blocks, navigate to page + scroll to block (simplified: just close for now)
                                    :block
                                    [{:op :update-node
                                      :id const/session-ui-id
                                      :props {:quick-switcher nil}}]

                                    ;; Default: just close
                                    [{:op :update-node
                                      :id const/session-ui-id
                                      :props {:quick-switcher nil}}])))))})

(intent/register-intent! :quick-switcher/close
                         {:doc "Close quick switcher without selecting (Esc)."
                          :spec [:map [:type [:= :quick-switcher/close]]]
                          :handler
                          (fn [db _]
                            [{:op :update-node
                              :id const/session-ui-id
                              :props {:quick-switcher nil}}])})
