(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [medley.core :as m]
            [lab.anki.fsrs :as fsrs]
            [core.time :as time])) ;; New import

;; Card parsing

(def cloze-pattern #"\[([^\]]+)\]")
(def image-occlusion-pattern #"^!\[([^\]]+)\]\(([^)]+)\)\s*\{([^}]+)\}$")

(defn parse-qa-multiline
  "Parse QA card from consecutive q/a lines"
  [lines]
  (let [line-map (->> lines
                      (map str/trim)
                      (filter #(re-find #"^(q|a) " %))
                      (map (fn [line] [(first line) (subs line 2)]))
                      (into {}))]
    (when (and (get line-map \q) (get line-map \a))
      {:question (str/trim (get line-map \q))
       :answer (str/trim (get line-map \a))})))

(def card-parsers
  "Registry of card parsers - add new card types here.
   Order matters: more specific patterns should come first."
  [{:type :edn
    :parse (fn [text]
             (try
               (let [data (edn/read-string text)]
                 ;; Only return if it's a valid card map with :type
                 (when (and (map? data) (:type data))
                   data))
               (catch #?(:clj Exception :cljs js/Error) _e
                 nil)))}
   {:type :image-occlusion
    :parse (fn [text]
             (when-let [[_ alt-text image-url regions] (re-matches image-occlusion-pattern text)]
               {:alt-text (str/trim alt-text)
                :image-url (str/trim image-url)
                :regions (str/split regions #",\s*")}))}
   {:type :cloze
    :parse (fn [text]
             (when (str/starts-with? (str/trim text) "c ")
               (let [content (subs (str/trim text) 2)]
                 (when-let [matches (re-seq cloze-pattern content)]
                   {:template content
                    :deletions (mapv second matches)}))))}
   {:type :qa
    :parse (fn [text]
             (parse-qa-multiline (str/split-lines text)))}])

(defn parse-card
  "Parse a card from markdown text. Returns nil if invalid."
  [text]
  (let [trimmed (str/trim text)]
    (some (fn [{card-type :type parse-fn :parse}]
            (when-let [data (parse-fn trimmed)]
              (assoc data :type card-type)))
          card-parsers)))

;; Card hashing

(defn card-hash
  "Generate a hash for a card based on its content"
  [card-data]
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                bs (.getBytes (pr-str card-data) "UTF-8")]
            (.update digest bs)
            (format "%064x" (BigInteger. 1 (.digest digest))))
     :cljs (let [s (pr-str card-data)]
             ;; Simple hash for CLJS (good enough for this use case)
             (str (cljs.core/hash s)))))

;; Card metadata

(defn new-card-meta
  "Create metadata for a new card"
  [h]
  {:card-hash h
   :created-at (time/date-from-ms (time/now-ms))
   :due-at (time/date-from-ms (time/now-ms))
   :reviews 0})

;; Scheduling (FSRS algorithm)

(defn schedule-card
  "Schedule next review using FSRS algorithm.
   Rating: :forgot :hard :good :easy
   review-time-ms: timestamp of the review (defaults to now)

   For first review, card-meta should not have :stability or :difficulty.
   For subsequent reviews, uses FSRS to calculate new stability/difficulty."
  ([card-meta rating]
   (schedule-card card-meta rating (time/now-ms)))
  ([card-meta rating review-time-ms]
   (let [;; Check if first review (no stability/difficulty yet)
         first-review? (nil? (:stability card-meta))

         ;; Calculate FSRS parameters
         fsrs-result (fsrs/schedule-next-review
                      (cond-> {:grade rating
                               :review-time-ms review-time-ms}
                        ;; Add current state for non-first reviews
                        (not first-review?)
                        (assoc :stability (:stability card-meta)
                               :difficulty (:difficulty card-meta)
                               :last-review-ms (or (:last-review-ms card-meta)
                                                   (.getTime (:created-at card-meta))))))

         ;; Build updated metadata
         new-meta (-> card-meta
                      (update :reviews inc)
                      (assoc :stability (:stability fsrs-result)
                             :difficulty (:difficulty fsrs-result)
                             :due-at (:due-at fsrs-result)
                             :interval-days (:interval fsrs-result)
                             :last-rating rating
                             :last-review-ms review-time-ms))]
     new-meta)))

;; Event log

(defn new-event
  "Create a new event with unique ID"
  [event-type data]
  {:event/id (random-uuid)
   :event/type event-type
   :event/timestamp (time/date-from-ms (time/now-ms))
   :event/data data})

(defn review-event
  "Create a review event"
  [h rating]
  (new-event :review {:card-hash h
                      :rating rating}))

(defn card-created-event
  "Create a card-created event - stores only hash and deck reference, not content"
  [h deck]
  (new-event :card-created {:card-hash h
                            :deck deck}))

(defn undo-event
  "Create an undo event that marks a target event as undone"
  [target-event-id]
  (new-event :undo {:target-event-id target-event-id}))

(defn redo-event
  "Create a redo event that marks an undone event as active again"
  [target-event-id]
  (new-event :redo {:target-event-id target-event-id}))

;; Image occlusion helpers

(defn expand-image-occlusion
  "Expand an image-occlusion card into virtual child cards for each occlusion.
   Returns a map of {child-hash virtual-card-data}."
  [parent-hash card]
  (when (= :image-occlusion (:type card))
    (let [occlusions (:occlusions card)]
      (reduce (fn [acc occlusion]
                (let [child-id [parent-hash (:oid occlusion)]
                      child-hash (card-hash child-id)]
                  (assoc acc child-hash
                         {:type :image-occlusion/item
                          :parent-id parent-hash
                          :occlusion-oid (:oid occlusion)
                          :asset (:asset card)
                          :prompt (:prompt card)
                          :shape (:shape occlusion)
                          :answer (:answer occlusion)})))
              {}
              occlusions))))

;; State reduction

(defn apply-event
  "Apply an event to the current state.
   Note: Cards are loaded from files, events only track metadata."
  [state event]
  (case (:event/type event)
    :card-created
    ;; Just create metadata entry - card content comes from files
    (let [{h :card-hash} (:event/data event)]
      (assoc-in state [:meta h] (new-card-meta h)))

    :review
    (let [{h :card-hash rating :rating} (:event/data event)
          card-meta (get-in state [:meta h])
          review-time-ms (.getTime (:event/timestamp event))]
      (if card-meta
        (assoc-in state [:meta h] (schedule-card card-meta rating review-time-ms))
        state))

    ;; Unknown event type - ignore
    state))

(defn build-event-status-map
  "Build a map of event-id -> status (:active or :undone)"
  [events]
  (reduce
   (fn [status-map event]
     (case (:event/type event)
       :undo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :undone)
       :redo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :active)
       status-map))
   {}
   events))

(defn build-undo-redo-stacks
  "Build undo and redo stacks from events.
   Only review events are undoable - card creation is not undoable during review sessions."
  [events event-status]
  (reduce
   (fn [{:keys [undo-stack redo-stack undone-set] :as stacks} event]
     (let [event-id (:event/id event)]
       (case (:event/type event)
         :review
         (if (contains? undone-set event-id)
           stacks ; Don't add undone events to undo stack
           {:undo-stack (conj undo-stack event-id)
            :redo-stack [] ; Clear redo stack on new action
            :undone-set undone-set})

         :card-created
         stacks ; Card creation is not undoable

         :undo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (vec (remove #{target-id} undo-stack))
            :redo-stack (conj redo-stack target-id)
            :undone-set (conj undone-set target-id)})

         :redo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (conj undo-stack target-id)
            :redo-stack (vec (remove #{target-id} redo-stack))
            :undone-set (disj undone-set target-id)})

         stacks)))
   {:undo-stack [] :redo-stack [] :undone-set #{}}
   events))

(defn reduce-events
  "Reduce a sequence of events to produce the current state with undo/redo support"
  [events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(let [event-id (:event/id %)
                                     status (get event-status event-id :active)]
                                 (and (= status :active)
                                      (#{:card-created :review} (:event/type %))))
                              events)
        {:keys [undo-stack redo-stack]} (build-undo-redo-stacks events event-status)
        base-state (reduce apply-event
                           {:cards {}
                            :meta {}}
                           active-events)]
    (merge base-state {:undo-stack undo-stack :redo-stack redo-stack})))

;; Query helpers

(defn due-cards
  "Get all cards that are due for review"
  [state]
  (let [now (time/now-ms)]
    (->> (:meta state)
         (m/filter-vals (fn [card-meta]
                          (<= (.getTime (:due-at card-meta)) now)))
         keys
         vec)))

(defn card-with-meta
  "Get card data with its metadata"
  [state h]
  (when-let [card (get-in state [:cards h])]
    (assoc card :meta (get-in state [:meta h]))))

;; Statistics

(defn compute-stats
  "Compute statistics from state and events"
  [state events]
  (let [today-start (time/today-start-ms)
        event-status-map (build-event-status-map events)
        active-events (filter #(= :active (get event-status-map (:event/id %) :active))
                              events)
        review-events (filter #(= :review (:event/type %)) active-events)
        today-reviews (filter #(>= (.getTime (:event/timestamp %)) today-start) review-events)

        ;; Card counts (only count cards that exist in files)
        card-hashes (set (keys (:cards state)))
        total-cards (count (:cards state))
        total-meta (count (m/filter-keys card-hashes (:meta state)))
        due-now (->> (due-cards state)
                     (filter card-hashes) ; Only count cards that exist in files
                     count)
        new-cards (->> (:meta state)
                       (m/filter-keys card-hashes) ; Only count existing cards
                       (m/filter-vals #(zero? (:reviews %)))
                       count)

        ;; Review counts
        total-reviews (count review-events)
        reviews-today (count today-reviews)

        ;; Ratings breakdown (all time)
        ratings-breakdown (->> review-events
                               (map #(get-in % [:event/data :rating]))
                               frequencies)

        ;; Retention (good+easy vs total)
        good-or-easy (+ (get ratings-breakdown :good 0)
                        (get ratings-breakdown :easy 0))
        retention-rate (if (pos? total-reviews)
                         (/ good-or-easy total-reviews)
                         0.0)

        ;; Average reviews per card
        avg-reviews-per-card (if (pos? total-meta)
                               (/ total-reviews total-meta)
                               0.0)]

    {:total-cards total-cards
     :due-now due-now
     :new-cards new-cards
     :total-reviews total-reviews
     :reviews-today reviews-today
     :retention-rate retention-rate
     :avg-reviews-per-card avg-reviews-per-card
     :ratings-breakdown ratings-breakdown}))

;; Validation

(defn check-integrity
  "Check data integrity and return a report of issues"
  [state events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(= :active (get event-status (:event/id %) :active)) events)

        ;; Find orphaned events (events for cards not in files)
        card-hashes (set (keys (:cards state)))
        orphaned-events (->> active-events
                             (filter #(#{:card-created :review} (:event/type %)))
                             (map (fn [e]
                                    (case (:event/type e)
                                      :card-created (get-in e [:event/data :card-hash])
                                      :review (get-in e [:event/data :card-hash])
                                      nil)))
                             (remove nil?)
                             (remove card-hashes)
                             distinct
                             vec)

        ;; Find cards without metadata (in files but not in events)
        meta-hashes (set (keys (:meta state)))
        cards-without-meta (->> card-hashes
                                (remove meta-hashes)
                                vec)

        ;; Check for duplicate event IDs
        event-ids (map :event/id events)
        duplicate-ids (->> event-ids
                           frequencies
                           (filter #(> (val %) 1))
                           (map key)
                           vec)

        ;; Count active vs undone events
        active-count (count active-events)
        undone-count (- (count events) active-count)

        issues []]

    (cond-> issues
      (seq orphaned-events)
      (conj {:type :orphaned-events
             :severity :warning
             :message (str "Found " (count orphaned-events) " orphaned events (cards deleted from files)")
             :data orphaned-events})

      (seq cards-without-meta)
      (conj {:type :cards-without-meta
             :severity :info
             :message (str "Found " (count cards-without-meta) " cards without metadata (new cards)")
             :data cards-without-meta})

      (seq duplicate-ids)
      (conj {:type :duplicate-event-ids
             :severity :error
             :message (str "Found " (count duplicate-ids) " duplicate event IDs")
             :data duplicate-ids}))))


