(ns lab.srs.indexes
  "SRS-specific derived indexes.
   Extends kernel's derive-indexes pattern with SRS data views."
  (:require [medley.core :as m]))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn nodes-by-type
  "Return map of nodes filtered by type.
   Returns seq of [node-id node] tuples for easy reduce/map operations."
  [db node-type]
  (->> (:nodes db)
       (m/filter-vals #(= node-type (:type %)))))

(defn child-ids-by-type
  "Filter child IDs by node type.
   Takes a seq of child IDs and returns only those matching node-type."
  [db child-ids node-type]
  (filter #(= node-type (get-in db [:nodes % :type])) child-ids))

(defn children-of-parent
  "Get children IDs for a parent node ID.
   Returns empty vector if parent has no children."
  [db parent-id]
  (get-in db [:children-by-parent parent-id] []))

;; ============================================================================
;; Derived Index Computation
;; ============================================================================

(defn compute-due-cards
  "Index cards by due date for scheduling queries.
   Returns: {:srs/due-index {due-date #{card-id ...}}}"
  [db]
  (let [cards (nodes-by-type db :card)
        due-index (->> cards
                       (keep (fn [[card-id node]]
                               (when-let [due-date (get-in node [:props :srs/due-date])]
                                 [due-date card-id])))
                       (reduce (fn [idx [due-date card-id]]
                                 (update idx due-date (fnil conj #{}) card-id))
                               {}))]
    {:srs/due-index due-index}))

(defn compute-cards-by-deck
  "Index cards by their parent deck.
   Returns: {:srs/cards-by-deck {deck-id #{card-id ...}}}"
  [db]
  (let [decks (nodes-by-type db :deck)
        cards-by-deck (->> decks
                           (map (fn [[deck-id _node]]
                                  (let [children (children-of-parent db deck-id)
                                        card-children (child-ids-by-type db children :card)]
                                    [deck-id (set card-children)])))
                           (into {}))]
    {:srs/cards-by-deck cards-by-deck}))

(defn compute-review-history
  "Index review history by card.
   Returns: {:srs/review-history {card-id [review-id ...]}}"
  [db]
  (let [cards (nodes-by-type db :card)
        review-history (->> cards
                            (keep (fn [[card-id _node]]
                                    (let [children (children-of-parent db card-id)
                                          reviews (child-ids-by-type db children :review)]
                                      (when (seq reviews)
                                        [card-id (vec reviews)]))))
                            (into {}))]
    {:srs/review-history review-history}))

(defn compute-scheduling-metadata
  "Compute aggregated scheduling metadata per card.
   Returns: {:srs/scheduling-metadata {card-id {:srs/interval-days N ...}}}"
  [db review-history]
  (let [cards (nodes-by-type db :card)
        metadata (->> cards
                      (map (fn [[card-id node]]
                             (let [reviews (get review-history card-id [])
                                   props (:props node)]
                               [card-id
                                {:srs/interval-days (get props :srs/interval-days 1)
                                 :srs/ease-factor (get props :srs/ease-factor 2.5)
                                 :srs/due-date (get props :srs/due-date)
                                 :srs/review-count (count reviews)}])))
                      (into {}))]
    {:srs/scheduling-metadata metadata}))

(defn compute-media-by-card
  "Index media nodes (for image-occlusion) by parent card.
   Returns: {:srs/media-by-card {card-id [{:id :props}]}}"
  [db]
  (let [cards (nodes-by-type db :card)
        media-by-card (->> cards
                           (keep (fn [[card-id _node]]
                                   (let [children (children-of-parent db card-id)
                                         media-children (->> (child-ids-by-type db children :media)
                                                             (map (fn [media-id]
                                                                    {:id media-id
                                                                     :props (get-in db [:nodes media-id :props])})))]
                                     (when (seq media-children)
                                       [card-id media-children]))))
                           (into {}))]
    {:srs/media-by-card media-by-card}))

;; ============================================================================
;; Main Derive Function
;; ============================================================================

(defn derive-srs-indexes
  "Compute all SRS-specific derived indexes.
   Merges results into db under :derived key."
  [db]
  (let [due-idx (compute-due-cards db)
        deck-idx (compute-cards-by-deck db)
        review-idx (compute-review-history db)
        review-history (:srs/review-history review-idx {})
        sched-idx (compute-scheduling-metadata db review-history)
        media-idx (compute-media-by-card db)]
    (update db :derived merge
            due-idx
            deck-idx
            review-idx
            sched-idx
            media-idx)))

;; ============================================================================
;; Query Helpers
;; ============================================================================

(defn get-due-cards
  "Get all cards due before a given date.
   Returns a set of card IDs."
  [db before-date]
  (let [due-index (get-in db [:derived :srs/due-index] {})]
    (->> due-index
         (m/filter-kv (fn [due-date _cards]
                        (neg? (compare due-date before-date))))
         vals
         (reduce into #{}))))

(defn get-deck-cards
  "Get all cards in a deck.
   Returns a set of card IDs."
  [db deck-id]
  (get-in db [:derived :srs/cards-by-deck deck-id] #{}))

(defn get-card-reviews
  "Get review history for a card.
   Returns a vector of review IDs in chronological order."
  [db card-id]
  (get-in db [:derived :srs/review-history card-id] []))

(defn get-card-scheduling
  "Get scheduling metadata for a card.
   Returns map with :srs/interval-days, :srs/ease-factor, etc."
  [db card-id]
  (get-in db [:derived :srs/scheduling-metadata card-id]))

(defn get-card-media
  "Get media nodes for a card (e.g., image-occlusion masks).
   Returns vector of media maps with :id and :props keys."
  [db card-id]
  (get-in db [:derived :srs/media-by-card card-id] []))
