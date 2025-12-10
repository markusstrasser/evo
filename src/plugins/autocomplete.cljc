(ns plugins.autocomplete
  "Generic autocomplete system with extensible source types.

   Provides intent handlers for autocomplete operations:
   - :autocomplete/trigger - Start autocomplete from trigger sequence
   - :autocomplete/update - Update query and filter results
   - :autocomplete/select - Select current item and insert
   - :autocomplete/dismiss - Close without selecting
   - :autocomplete/navigate - Move selection up/down

   Source types are extensible via multimethods:
   - search-items: Get filtered items for a query
   - insert-text: Generate text to insert for selected item
   - item-label: Get display label for an item"
  (:require [kernel.intent :as intent]
            [utils.fuzzy-search :as fuzzy]
            [clojure.string :as str]))

;; Sentinel for DCE prevention
(def loaded? true)

;; ── Multimethod Protocol ──────────────────────────────────────────────────────
;; Extend these to add new autocomplete sources (block-refs, commands, tags, etc.)

(defmulti search-items
  "Search for items matching query. Returns seq of items.

   Dispatch on source type (:page-ref, :block-ref, :command, etc.)

   Args: [db {:keys [type query]}]
   Returns: seq of items (shape depends on source type)"
  (fn [_db opts] (:type opts)))

(defmulti insert-text
  "Generate text to insert for selected item.

   Args: [db {:keys [type item query]}]
   Returns: string to insert (replaces trigger + query)"
  (fn [_db opts] (:type opts)))

(defmulti item-label
  "Get display label for an item.

   Args: [{:keys [type item]}]
   Returns: string label for display"
  (fn [opts] (:type opts)))

;; ── Page Ref Implementation ───────────────────────────────────────────────────

(defmethod search-items :page-ref
  [db {:keys [query]}]
  (let [pages (get-in db [:children-by-parent :doc] [])
        page-data (map (fn [id]
                         {:id id
                          :title (get-in db [:nodes id :props :title] "Untitled")})
                       pages)]
    (if (str/blank? query)
      (take 10 page-data)
      (fuzzy/fuzzy-filter page-data query :title 10))))

(defmethod insert-text :page-ref
  [_db {:keys [item]}]
  (str "[[" (:title item) "]]"))

(defmethod item-label :page-ref
  [{:keys [item]}]
  (:title item))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- get-block-text [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(intent/register-intent! :autocomplete/trigger
                         {:doc "Trigger autocomplete popup.

         Called when a trigger sequence is detected (e.g., [[).
         Initializes autocomplete state and performs initial search."

                          :spec [:map
                                 [:type [:= :autocomplete/trigger]]
                                 [:source [:enum :page-ref :block-ref :command :tag]]
                                 [:block-id :string]
                                 [:trigger-pos :int]]

                          :handler
                          (fn [db _session {:keys [source block-id trigger-pos]}]
                            (let [items (search-items db {:type source :query ""})]
                              {:session-updates
                               {:ui {:autocomplete {:type source
                                                    :block-id block-id
                                                    :trigger-pos trigger-pos
                                                    :query ""
                                                    :selected 0
                                                    :items (vec items)}}}}))})

(intent/register-intent! :autocomplete/update
                         {:doc "Update autocomplete query and filter results.

         Called on each keystroke while autocomplete is active."

                          :spec [:map
                                 [:type [:= :autocomplete/update]]
                                 [:query :string]]

                          :handler
                          (fn [db session {:keys [query]}]
                            (let [autocomplete (get-in session [:ui :autocomplete])
                                  source-type (:type autocomplete)
                                  items (search-items db {:type source-type :query query})]
                              {:session-updates
                               {:ui {:autocomplete {:query query
                                                    :selected 0
                                                    :items (vec items)}}}}))})

(intent/register-intent! :autocomplete/select
                         {:doc "Select current autocomplete item and insert.

         Replaces trigger + query with the selected item's text.
         Uses pending-buffer text (injected by executor) since we're in edit mode.
         
         Exits edit mode to force DOM sync, sets cursor-position for re-entry."

                          :spec [:map
                                 [:type [:= :autocomplete/select]]]

                          :handler
                          (fn [db session intent]
                            (let [autocomplete (get-in session [:ui :autocomplete])
                                  {:keys [type block-id trigger-pos query selected items]} autocomplete
                                  item (get items selected)
                                  ;; Use buffer text (injected by executor) since we're editing
                                  ;; Fall back to DB text if buffer not available
                                  buffer-text (get-in intent [:pending-buffer :text])
                                  db-text (get-block-text db block-id)
                                  text (or buffer-text db-text)]
                              (when (and autocomplete item text)
                                (let [;; Calculate what to replace: from trigger-pos to current cursor
                                      ;; trigger-pos is after [[ so we go back 2 chars
                                      start-pos (- trigger-pos 2)
                                      ;; End is trigger-pos + query length
                                      end-pos (+ trigger-pos (count query))
                                      insert-str (insert-text db {:type type :item item :query query})
                                      new-text (str (subs text 0 start-pos)
                                                    insert-str
                                                    (subs text end-pos))
                                      new-cursor (+ start-pos (count insert-str))]
                                  {:ops [{:op :update-node
                                          :id block-id
                                          :props {:text new-text}}]
                                   ;; Exit edit mode to force DOM sync from DB
                                   ;; User can click to re-enter if needed
                                   :session-updates
                                   {:ui {:autocomplete nil
                                         :editing-block-id nil
                                         :cursor-position new-cursor}
                                    :selection {:focus block-id}}}))))})

(intent/register-intent! :autocomplete/dismiss
                         {:doc "Dismiss autocomplete without selecting."

                          :spec [:map
                                 [:type [:= :autocomplete/dismiss]]]

                          :handler
                          (fn [_db _session _intent]
                            {:session-updates
                             {:ui {:autocomplete nil}}})})

(intent/register-intent! :autocomplete/navigate
                         {:doc "Navigate selection in autocomplete list.

         direction: :up or :down"

                          :spec [:map
                                 [:type [:= :autocomplete/navigate]]
                                 [:direction [:enum :up :down]]]

                          :handler
                          (fn [_db session {:keys [direction]}]
                            (let [autocomplete (get-in session [:ui :autocomplete])
                                  {:keys [selected items]} autocomplete
                                  max-idx (max 0 (dec (count items)))
                                  new-idx (case direction
                                            :up (max 0 (dec selected))
                                            :down (min max-idx (inc selected))
                                            selected)]
                              {:session-updates
                               {:ui {:autocomplete {:selected new-idx}}}}))})
