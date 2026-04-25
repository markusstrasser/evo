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
            [kernel.query :as q]
            [parser.page-refs :as page-refs]
            [utils.fuzzy-search :as fuzzy]
            [clojure.string :as str]
            #?(:cljs [utils.journal :as journal])))

;; ── Result Limits ─────────────────────────────────────────────────────────────

(def ^:private page-ref-result-limit 10)
(def ^:private command-result-limit 15)

;; Sentinel for DCE prevention

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
  :type)

;; ── Page Ref Implementation ───────────────────────────────────────────────────

(defn- valid-page-title?
  "Check if title is non-empty and not a placeholder."
  [title]
  (and title
       (not (str/blank? title))
       (not= title "Untitled")))

(defn- has-exact-match?
  "Check if query exactly matches any page title (case-insensitive)."
  [pages query]
  (let [query-lower (str/lower-case (or query ""))]
    (some #(= (str/lower-case (:title %)) query-lower) pages)))

(defmethod search-items :page-ref
  [db {:keys [query]}]
  (let [page-data (->> (get-in db [:children-by-parent :doc] [])
                       (map (fn [id]
                              {:id id
                               :title (get-in db [:nodes id :props :title])
                               :type :existing}))
                       (filter (comp valid-page-title? :title)))
        filtered (if (str/blank? query)
                   (take page-ref-result-limit page-data)
                   (fuzzy/fuzzy-filter page-data query :title page-ref-result-limit))
        create-option (when (and (not (str/blank? query))
                                 (not (has-exact-match? filtered query)))
                        {:id :new
                         :title query
                         :type :create-new})]
    (cond-> filtered
      create-option (conj create-option))))

(defmethod insert-text :page-ref
  [_db {:keys [item]}]
  (page-refs/format-ref (:title item)))

(defmethod item-label :page-ref
  [{:keys [item]}]
  (:title item))

;; ── Slash Command Implementation ─────────────────────────────────────────────

(def ^:private slash-commands
  "Registry of slash commands.
   Each command: {:id :keyword :name \"Display Name\" :description \"...\" :icon \"emoji\"
                  :insert-fn (fn [] \"text to insert\") :backward-pos int}"
  [{:id :today
    :name "Today"
    :description "[[Dec 10, 2025]]"
    :icon "📅"
    :category "Date & Time"
    :insert-fn #?(:cljs journal/today-page-ref
                  :clj (constantly "[[today]]"))}

   {:id :tomorrow
    :name "Tomorrow"
    :description "[[Dec 11, 2025]]"
    :icon "📆"
    :category "Date & Time"
    :insert-fn #?(:cljs journal/tomorrow-page-ref
                  :clj (constantly "[[tomorrow]]"))}

   {:id :yesterday
    :name "Yesterday"
    :description "[[Dec 9, 2025]]"
    :icon "📆"
    :category "Date & Time"
    :insert-fn #?(:cljs journal/yesterday-page-ref
                  :clj (constantly "[[yesterday]]"))}

   {:id :current-time
    :name "Time"
    :description "HH:MM"
    :icon "🕐"
    :category "Date & Time"
    :insert-fn #?(:cljs journal/current-time
                  :clj (constantly "00:00"))}

   {:id :date-picker
    :name "Date picker"
    :description "Calendar"
    :icon "📅"
    :category "Date & Time"
    :insert-fn #?(:cljs journal/today-page-ref
                  :clj (constantly "[[today]]"))}

   {:id :tweet
    :name "Tweet"
    :description "{{tweet URL}}"
    :icon "🐦"
    :category "Embeds"
    :insert-fn (constantly "{{tweet }}")
    :backward-pos 2}

   {:id :video
    :name "Video"
    :description "{{video URL}}"
    :icon "🎬"
    :category "Embeds"
    :insert-fn (constantly "{{video }}")
    :backward-pos 2}

   {:id :code
    :name "Code"
    :description "```lang```"
    :icon "💻"
    :category "Blocks"
    :insert-fn (constantly "```\n\n```")
    :backward-pos 4}

   {:id :quote
    :name "Quote"
    :description "> text"
    :icon "💬"
    :category "Blocks"
    :insert-fn (constantly "> ")}

   {:id :heading-1
    :name "H1"
    :description "# Large"
    :icon "H1"
    :category "Headings"
    :insert-fn (constantly "# ")}

   {:id :heading-2
    :name "H2"
    :description "## Medium"
    :icon "H2"
    :category "Headings"
    :insert-fn (constantly "## ")}

   {:id :heading-3
    :name "H3"
    :description "### Small"
    :icon "H3"
    :category "Headings"
    :insert-fn (constantly "### ")}])

(defmethod search-items :command
  [_db {:keys [query]}]
  (if (str/blank? query)
    slash-commands
    (fuzzy/fuzzy-filter slash-commands query :name command-result-limit)))

(defmethod insert-text :command
  [_db {:keys [item]}]
  ((:insert-fn item)))

(defmethod item-label :command
  [{:keys [item]}]
  (:name item))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(intent/register-intent! :autocomplete/trigger
                         {:doc "Trigger autocomplete popup.

         Called when a trigger sequence is detected (e.g., [[ or /).
         Initializes autocomplete state and performs initial search.

         trigger-length: Length of trigger chars (2 for [[, 1 for /)"

                          :fr/ids #{:fr.ui/slash-palette}

                          :spec [:map
                                 [:type [:= :autocomplete/trigger]]
                                 [:source [:enum :page-ref :block-ref :command :tag]]
                                 [:block-id :string]
                                 [:trigger-pos :int]
                                 [:trigger-length {:optional true} :int]]

                          :handler
                          (fn [db _session {:keys [source block-id trigger-pos trigger-length]}]
                            (let [items (search-items db {:type source :query ""})
                                  ;; Default trigger-length based on source
                                  trig-len (or trigger-length
                                               (case source
                                                 :page-ref 2    ; [[
                                                 :command 1     ; /
                                                 :tag 1         ; #
                                                 2))]
                              {:session-updates
                               {:ui {:autocomplete {:type source
                                                    :block-id block-id
                                                    :trigger-pos trigger-pos
                                                    :trigger-length trig-len
                                                    :query ""
                                                    :selected 0
                                                    :items (vec items)}}}}))})

(intent/register-intent! :autocomplete/update
                         {:doc "Update autocomplete query and filter results.

         Called on each keystroke while autocomplete is active."

                          :fr/ids #{:fr.ui/slash-filter}

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

(defn- calculate-replacement-range
  "Calculate start and end positions for text replacement."
  [trigger-pos trigger-length query source-type]
  (let [start-pos (- trigger-pos trigger-length)
        closing-chars (if (= source-type :page-ref) 2 0)
        end-pos (+ trigger-pos (count query) closing-chars)]
    [start-pos end-pos]))

(defn- replace-text-segment
  "Replace text segment between start and end with insert string."
  [text start-pos end-pos insert-str]
  (str (subs text 0 start-pos)
       insert-str
       (subs text (min end-pos (count text)))))

(intent/register-intent! :autocomplete/select
                         {:doc "Select current autocomplete item and insert.

         Replaces trigger + query with the selected item's text.
         Uses pending-buffer text (injected by executor) since we're in edit mode.

         For commands: Uses trigger-length (1 for /) and item's backward-pos if set.
         Exits edit mode to force DOM sync, sets cursor-position for re-entry."

                          :fr/ids #{:fr.ui/slash-select}

                          :spec [:map
                                 [:type [:= :autocomplete/select]]]

                          :handler
                          (fn [db session intent]
                            (let [autocomplete (get-in session [:ui :autocomplete])
                                  {:keys [type block-id trigger-pos trigger-length query selected items]} autocomplete
                                  item (get items selected)
                                  text (or (get-in intent [:pending-buffer :text])
                                           (q/block-text db block-id))
                                  trig-len (or trigger-length 2)]
                              (when (and autocomplete item text)
                                (let [[start-pos end-pos] (calculate-replacement-range trigger-pos trig-len query type)
                                      insert-str (insert-text db {:type type :item item :query query})
                                      new-text (replace-text-segment text start-pos end-pos insert-str)
                                      item-backward (get item :backward-pos 0)
                                      new-cursor (- (+ start-pos (count insert-str)) item-backward)]
                                  {:ops [{:op :update-node
                                          :id block-id
                                          :props {:text new-text}}]
                                   :session-updates
                                   {:ui {:autocomplete nil
                                         :editing-block-id nil
                                         :cursor-position new-cursor}
                                    :selection {:focus block-id}}}))))})

(intent/register-intent! :autocomplete/dismiss
                         {:doc "Dismiss autocomplete without selecting."

                          :fr/ids #{:fr.ui/slash-close}

                          :spec [:map
                                 [:type [:= :autocomplete/dismiss]]]

                          :handler
                          (fn [_db _session _intent]
                            {:session-updates
                             {:ui {:autocomplete nil}}})})

(intent/register-intent! :autocomplete/navigate
                         {:doc "Navigate selection in autocomplete list.

         direction: :up or :down"

                          :fr/ids #{:fr.ui/slash-navigate}

                          :spec [:map
                                 [:type [:= :autocomplete/navigate]]
                                 [:direction [:enum :up :down]]]

                          :handler
                          (fn [_db session {:keys [direction]}]
                            (let [autocomplete (get-in session [:ui :autocomplete])
                                  {:keys [selected items]} autocomplete
                                  max-idx (max 0 (dec (count items)))
                                  delta (case direction :up -1 :down 1 0)
                                  new-idx (-> selected (+ delta) (max 0) (min max-idx))]
                              {:session-updates
                               {:ui {:autocomplete {:selected new-idx}}}}))})

(defn- generate-page-id
  "Generate a unique page ID from title.
   Converts to lowercase, replaces spaces with hyphens."
  [title]
  (-> title
      str/lower-case
      (str/replace #"\s+" "-")
      (str/replace #"[^a-z0-9-]" "")
      (str "-" (rand-int 10000))))

(intent/register-intent! :page/create
                         {:doc "Create a new page and optionally navigate to it.

         Creates a page node under :doc with the given title."

                          :fr/ids #{:fr.pages/switch-page}

                          :spec [:map
                                 [:type [:= :page/create]]
                                 [:title :string]
                                 [:navigate? {:optional true} :boolean]]

                          :handler
                          (fn [_db _session {:keys [title navigate?]}]
                            (let [page-id (generate-page-id title)]
                              {:ops [{:op :create-node
                                      :id page-id
                                      :type :page
                                      :props {:title title}}
                                     {:op :place
                                      :id page-id
                                      :under :doc
                                      :at :last}]
                               :session-updates
                               (when navigate?
                                 {:ui {:current-page page-id}})}))})

;; NOTE: :navigate-to-page intent is registered in plugins.pages
;; (canonical location for page navigation logic)


;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
