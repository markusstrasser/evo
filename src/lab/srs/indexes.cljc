(ns lab.srs.indexes
  "SRS-specific derived indexes.
   Extends kernel's derive-indexes pattern with SRS data views.")

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn nodes-by-type
  "Filter nodes by type from db."
  [db node-type]
  (filter #(= node-type (:type (val %))) (:nodes db)))

(defn children-by-type
  "Filter children IDs by type."
  [db child-ids node-type]
  (filter #(= node-type (get-in db [:nodes % :type])) child-ids))

(defn wrap-index
  "Wrap computed index result in a namespaced key map."
  [k v]
  {k v})

;; ============================================================================
;; Derived Index Computation
;; ============================================================================

(defn compute-due-cards
  "Index cards by due date for scheduling queries.
   Returns: {:due-index {due-date #{card-id ...}}}"
  [db]
  (let [cards (nodes-by-type db :card)
        due-index (reduce
                   (fn [idx [card-id node]]
                     (if-let [due-date (get-in node [:props :srs/due-date])]
                       (update idx due-date (fnil conj #{}) card-id)
                       idx))
                   {}
                   cards)]
    (wrap-index :srs/due-index due-index)))

(defn compute-cards-by-deck
  "Index cards by their parent deck.
   Returns: {:cards-by-deck {deck-id #{card-id ...}}}"
  [db]
  (let [children-by-parent (:children-by-parent db)
        decks (nodes-by-type db :deck)
        cards-by-deck (reduce
                       (fn [idx [deck-id _node]]
                         (let [children (get children-by-parent deck-id [])
                               card-children (children-by-type db children :card)]
                           (assoc idx deck-id (set card-children))))
                       {}
                       decks)]
    (wrap-index :srs/cards-by-deck cards-by-deck)))

(defn compute-review-history
  "Index review history by card.
   Returns: {:review-history {card-id [review-id ...]}}"
  [db]
  (let [children-by-parent (:children-by-parent db)
        cards (nodes-by-type db :card)
        review-history (reduce
                        (fn [idx [card-id _node]]
                          (let [children (get children-by-parent card-id [])
                                review-children (children-by-type db children :review)]
                            (if (seq review-children)
                              (assoc idx card-id (vec review-children))
                              idx)))
                        {}
                        cards)]
    (wrap-index :srs/review-history review-history)))

(defn compute-scheduling-metadata
  "Compute aggregated scheduling metadata per card.
   Returns: {:scheduling-metadata {card-id {:interval-days N :ease-factor F :review-count N}}}"
  [db review-history]
  (let [cards (nodes-by-type db :card)
        metadata (reduce
                  (fn [idx [card-id node]]
                    (let [reviews (get review-history card-id [])
                          review-count (count reviews)
                          props (:props node)]
                      (assoc idx card-id
                             {:srs/interval-days (get props :srs/interval-days 1)
                              :srs/ease-factor (get props :srs/ease-factor 2.5)
                              :srs/due-date (get props :srs/due-date)
                              :srs/review-count review-count})))
                  {}
                  cards)]
    (wrap-index :srs/scheduling-metadata metadata)))

(defn compute-media-by-card
  "Index media nodes (for image-occlusion) by parent card.
   Returns: {:media-by-card {card-id [{:id :props}]}}"
  [db]
  (let [children-by-parent (:children-by-parent db)
        cards (nodes-by-type db :card)
        media-by-card (reduce
                       (fn [idx [card-id _node]]
                         (let [children (get children-by-parent card-id [])
                               media-children (->> (children-by-type db children :media)
                                                   (map #(hash-map :id % :props (get-in db [:nodes % :props]))))]
                           (if (seq media-children)
                             (assoc idx card-id media-children)
                             idx)))
                       {}
                       cards)]
    (wrap-index :srs/media-by-card media-by-card)))

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
        review-history (get review-idx :srs/review-history {})
        sched-idx (compute-scheduling-metadata db review-history)
        media-idx (compute-media-by-card db)]
    ;; Merge all indexes into :derived
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
  "Get all cards due before a given date."
  [db before-date]
  (let [due-index (get-in db [:derived :srs/due-index] {})]
    (->> due-index
         (filter (fn [[due-date _cards]]
                   (neg? (compare due-date before-date))))
         (mapcat val)
         set)))

(defn get-deck-cards
  "Get all cards in a deck."
  [db deck-id]
  (get-in db [:derived :srs/cards-by-deck deck-id] #{}))

(defn get-card-reviews
  "Get review history for a card."
  [db card-id]
  (get-in db [:derived :srs/review-history card-id] []))

(defn get-card-scheduling
  "Get scheduling metadata for a card."
  [db card-id]
  (get-in db [:derived :srs/scheduling-metadata card-id]))

(defn get-card-media
  "Get media nodes for a card (e.g., image-occlusion masks)."
  [db card-id]
  (get-in db [:derived :srs/media-by-card card-id] []))
