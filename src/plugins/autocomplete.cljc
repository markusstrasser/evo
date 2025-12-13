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
            [utils.fuzzy-search :as fuzzy]
            [clojure.string :as str]))

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
  (fn [opts] (:type opts)))

;; ── Page Ref Implementation ───────────────────────────────────────────────────

(defmethod search-items :page-ref
  [db {:keys [query]}]
  (let [pages (get-in db [:children-by-parent :doc] [])
        page-data (map (fn [id]
                         {:id id
                          :title (get-in db [:nodes id :props :title] "Untitled")
                          :type :existing})
                       pages)
        ;; Filter existing pages
        filtered (if (str/blank? query)
                   (take 10 page-data)
                   (fuzzy/fuzzy-filter page-data query :title 10))
        ;; Check if query exactly matches an existing page title (case-insensitive)
        query-lower (str/lower-case (or query ""))
        exact-match? (some #(= (str/lower-case (:title %)) query-lower) filtered)
        ;; Add "Create new page" option if query is non-empty and no exact match
        create-option (when (and (not (str/blank? query))
                                 (not exact-match?))
                        {:id :new
                         :title query
                         :type :create-new})]
    ;; Put create option at end
    (if create-option
      (conj (vec filtered) create-option)
      filtered)))

(defmethod insert-text :page-ref
  [_db {:keys [item]}]
  (str "[[" (:title item) "]]"))

(defmethod item-label :page-ref
  [{:keys [item]}]
  (:title item))

;; ── Slash Command Implementation ─────────────────────────────────────────────

(defn- format-date
  "Format a date as a page reference string like [[Dec 10, 2025]]"
  [date-obj]
  #?(:cljs
     (let [months ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
                   "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
           month (nth months (.getMonth date-obj))
           day (.getDate date-obj)
           year (.getFullYear date-obj)]
       (str "[[" month " " day ", " year "]]"))
     :clj
     (do
       (assert date-obj "date-obj required") ; Silence unused binding warning
       (str "[[" (java.time.LocalDate/now) "]]"))))

(defn- get-today []
  #?(:cljs (js/Date.) :clj (java.util.Date.)))

(defn- get-tomorrow []
  #?(:cljs
     (let [d (js/Date.)]
       (.setDate d (inc (.getDate d)))
       d)
     :clj
     (java.util.Date.)))

(defn- get-yesterday []
  #?(:cljs
     (let [d (js/Date.)]
       (.setDate d (dec (.getDate d)))
       d)
     :clj
     (java.util.Date.)))

(defn- get-current-time []
  #?(:cljs
     (let [d (js/Date.)
           hours (.getHours d)
           minutes (.getMinutes d)
           pad #(if (< % 10) (str "0" %) (str %))]
       (str (pad hours) ":" (pad minutes)))
     :clj
     "00:00"))

(def ^:private slash-commands
  "Registry of slash commands.
   Each command: {:id :keyword :name \"Display Name\" :description \"...\" :icon \"emoji\"
                  :insert-fn (fn [] \"text to insert\") :backward-pos int}"
  [{:id :today
    :name "Today"
    :description "[[Dec 10, 2025]]"
    :icon "📅"
    :category "Date & Time"
    :insert-fn #(format-date (get-today))}

   {:id :tomorrow
    :name "Tomorrow"
    :description "[[Dec 11, 2025]]"
    :icon "📆"
    :category "Date & Time"
    :insert-fn #(format-date (get-tomorrow))}

   {:id :yesterday
    :name "Yesterday"
    :description "[[Dec 9, 2025]]"
    :icon "📆"
    :category "Date & Time"
    :insert-fn #(format-date (get-yesterday))}

   {:id :current-time
    :name "Time"
    :description "HH:MM"
    :icon "🕐"
    :category "Date & Time"
    :insert-fn get-current-time}

   {:id :date-picker
    :name "Date picker"
    :description "Calendar"
    :icon "📅"
    :category "Date & Time"
    :insert-fn #(format-date (get-today))}  ; TODO: Show actual date picker

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
    (fuzzy/fuzzy-filter slash-commands query :name 15)))

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
                                  ;; Use buffer text (injected by executor) since we're editing
                                  ;; Fall back to DB text if buffer not available
                                  buffer-text (get-in intent [:pending-buffer :text])
                                  db-text (q/block-text db block-id)
                                  text (or buffer-text db-text)
                                  ;; Default trigger-length for backward compat
                                  trig-len (or trigger-length 2)]
                              (when (and autocomplete item text)
                                (let [;; Calculate what to replace: from trigger start to end of query
                                      ;; trigger-pos is AFTER trigger chars, so go back by trigger-length
                                      start-pos (- trigger-pos trig-len)
                                      ;; End is trigger-pos + query length (for page-ref, also skip closing ]])
                                      end-pos (+ trigger-pos (count query)
                                                 (if (= type :page-ref) 2 0))
                                      insert-str (insert-text db {:type type :item item :query query})
                                      new-text (str (subs text 0 start-pos)
                                                    insert-str
                                                    (subs text (min end-pos (count text))))
                                      ;; For commands, apply item's backward-pos if set
                                      item-backward (get item :backward-pos 0)
                                      new-cursor (- (+ start-pos (count insert-str)) item-backward)]
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
                                  new-idx (case direction
                                            :up (max 0 (dec selected))
                                            :down (min max-idx (inc selected))
                                            selected)]
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
      (str "-" (rand-int 10000)))) ; Add random suffix for uniqueness

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

(defn- find-page-by-title
  "Find a page ID by its title (case-insensitive)."
  [db title]
  (let [pages (get-in db [:children-by-parent :doc] [])
        title-lower (str/lower-case title)]
    (some (fn [id]
            (let [page-title (get-in db [:nodes id :props :title] "")]
              (when (= (str/lower-case page-title) title-lower)
                id)))
          pages)))

(intent/register-intent! :navigate-to-page
                         {:doc "Navigate to a page by name.

         If page exists, sets it as current page.
         If page doesn't exist, creates it first then navigates."

                          :fr/ids #{:fr.pages/switch-page}

                          :spec [:map
                                 [:type [:= :navigate-to-page]]
                                 [:page-name :string]]

                          :handler
                          (fn [db _session {:keys [page-name]}]
                            (if-let [page-id (find-page-by-title db page-name)]
                              ;; Page exists - just navigate
                              {:session-updates
                               {:ui {:current-page page-id
                                     :editing-block-id nil}
                                :selection {:focus nil :anchor nil :nodes #{}}}}
                              ;; Page doesn't exist - create and navigate
                              (let [new-page-id (generate-page-id page-name)]
                                {:ops [{:op :create-node
                                        :id new-page-id
                                        :type :page
                                        :props {:title page-name}}
                                       {:op :place
                                        :id new-page-id
                                        :under :doc
                                        :at :last}]
                                 :session-updates
                                 {:ui {:current-page new-page-id
                                       :editing-block-id nil}
                                  :selection {:focus nil :anchor nil :nodes #{}}}})))})


;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
