(ns lab.srs.demo
  "SRS demo with mock data and assertions.
   Tests the complete flow: intents → kernel ops → derived indexes → log."
  (:require [core.db :as db]
            [core.interpret :as interpret]
            [lab.srs.schema :as schema]
            [lab.srs.plugin :as plugin]
            [lab.srs.indexes :as indexes]
            [lab.srs.log :as log]))

;; ============================================================================
;; Test Setup
;; ============================================================================

(defn fresh-db
  "Create a fresh kernel DB with a deck root."
  []
  (let [db0 (db/empty-db)
        ;; Add :decks root (like :doc, :trash)
        db1 (assoc db0 :roots (conj (:roots db0) :decks))]
    db1))

(defn run-srs-transaction
  "Run an SRS intent through the full pipeline:
   1. Validate schema
   2. Compile to kernel ops (with plugin support)
   3. Interpret via kernel
   4. Derive SRS indexes
   5. Log transaction
   Returns: {:db, :log-entry}"
  [db intent]
  (when-not (schema/valid-srs-op? intent)
    (throw (ex-info "Invalid SRS intent"
                    {:intent intent
                     :explanation (schema/explain-srs-op intent)})))

  (let [;; Compile SRS intent → kernel ops (with plugin support)
        kernel-ops (plugin/compile-with-plugin intent db)

        ;; Run through kernel pipeline
        result (interpret/interpret db kernel-ops)
        db-after (:db result)
        issues (:issues result)

        _ (when (seq issues)
            (throw (ex-info "Kernel validation failed"
                            {:issues issues :intent intent})))

        ;; Derive SRS indexes
        db-with-indexes (indexes/derive-srs-indexes db-after)

        ;; Log transaction
        log-entry (log/record-transaction!
                   {:intent intent
                    :compiled-ops kernel-ops
                    :db-before db
                    :db-after db-with-indexes
                    :source :test
                    :actor :demo})]

    {:db db-with-indexes
     :log-entry log-entry}))

;; ============================================================================
;; Demo Scenario
;; ============================================================================

(defn demo-basic-workflow
  "Demo: Create deck, add cards, review, verify indexes."
  []
  (println "\n=== SRS Demo: Basic Workflow ===\n")

  ;; Setup
  (log/reset-log!)
  (plugin/init-default-plugins!)

  (let [;; Step 1: Create deck
        db0 (fresh-db)
        deck-id "biology-deck"

        _ (println "1. Creating deck...")
        deck-ops [{:op :create-node
                   :id deck-id
                   :type :deck
                   :props {:name "Biology Basics"
                           :markdown-dir "decks/biology/"}}
                  {:op :place
                   :id deck-id
                   :under :decks
                   :at :last}]

        deck-result (interpret/interpret db0 deck-ops)
        db1 (:db deck-result)

        ;; Step 2: Create first card
        _ (println "2. Creating card: 'What is DNA?'")
        card1-intent {:op :srs/create-card
                      :card-id "card-dna"
                      :deck-id deck-id
                      :card-type :basic
                      :markdown-file "decks/biology/dna.md"
                      :front "What is DNA?"
                      :back "Deoxyribonucleic acid"
                      :tags ["genetics" "basics"]}

        {db2 :db} (run-srs-transaction db1 card1-intent)

        ;; Assertion: Card exists with content child
        _ (assert (contains? (:nodes db2) "card-dna")
                  "Card node should exist")
        _ (let [card-children (get-in db2 [:children-by-parent "card-dna"])
                content-children (filter #(= :card-content
                                             (get-in db2 [:nodes % :type]))
                                         card-children)]
            (assert (= 1 (count content-children))
                    "Card should have exactly 1 content child"))

        _ (println "   ✓ Card created with separate content node")

        ;; Step 3: Create second card
        _ (println "3. Creating card: 'What is RNA?'")
        card2-intent {:op :srs/create-card
                      :card-id "card-rna"
                      :deck-id deck-id
                      :card-type :basic
                      :markdown-file "decks/biology/rna.md"
                      :front "What is RNA?"
                      :back "Ribonucleic acid"
                      :tags ["genetics"]}

        {db3 :db} (run-srs-transaction db2 card2-intent)

        ;; Assertion: Deck has 2 cards
        _ (let [deck-cards (indexes/get-deck-cards db3 deck-id)]
            (assert (= 2 (count deck-cards))
                    (str "Deck should have 2 cards, got " (count deck-cards))))

        _ (println "   ✓ Deck now has 2 cards")

        ;; Step 4: Review first card (grade: good)
        _ (println "4. Reviewing 'What is DNA?' with grade :good")
        review-intent {:op :srs/review-card
                       :card-id "card-dna"
                       :grade :good
                       :timestamp #?(:clj (str (java.time.Instant/now))
                                     :cljs (.toISOString (js/Date.)))
                       :latency-ms 3500}

        {db4 :db} (run-srs-transaction db3 review-intent)

        ;; Assertion: Review history exists
        _ (let [reviews (indexes/get-card-reviews db4 "card-dna")]
            (assert (= 1 (count reviews))
                    "Card should have 1 review"))

        ;; Assertion: Scheduling updated
        _ (let [sched (indexes/get-card-scheduling db4 "card-dna")]
            (assert (= 7 (:srs/interval-days sched))
                    "Good review should set 7-day interval")
            (assert (= 2.5 (:srs/ease-factor sched))
                    "Good review should set ease factor 2.5"))

        _ (println "   ✓ Review recorded, interval updated to 7 days")

        ;; Step 5: Update card content
        _ (println "5. Updating card content...")
        update-intent {:op :srs/update-card
                       :card-id "card-dna"
                       :props {:tags ["genetics" "basics" "molecules"]}}

        {db5 :db} (run-srs-transaction db4 update-intent)

        _ (let [tags (get-in db5 [:nodes "card-dna" :props :tags])]
            (assert (= 3 (count tags))
                    "Card should have 3 tags after update"))

        _ (println "   ✓ Card tags updated")

        ;; Step 6: Review second card (grade: again)
        _ (println "6. Reviewing 'What is RNA?' with grade :again")
        review2-intent {:op :srs/review-card
                        :card-id "card-rna"
                        :grade :again
                        :timestamp #?(:clj (str (java.time.Instant/now))
                                      :cljs (.toISOString (js/Date.)))
                        :latency-ms 15000}

        {db6 :db} (run-srs-transaction db5 review2-intent)

        _ (let [sched (indexes/get-card-scheduling db6 "card-rna")]
            (assert (= 1 (:srs/interval-days sched))
                    "Again review should set 1-day interval"))

        _ (println "   ✓ Review recorded, interval reset to 1 day")

        ;; Step 7: Verify log
        _ (println "\n7. Verifying append-only log...")
        _ (let [log-entries (log/get-log)
                review-entries (log/get-recent-reviews 10)]
            (assert (= 5 (count log-entries))
                    (str "Log should have 5 entries, got " (count log-entries)))
            (assert (= 2 (count review-entries))
                    "Log should have 2 review entries"))

        _ (println "   ✓ Log has 5 entries (2 creates, 1 update, 2 reviews)")

        ;; Step 8: Test undo capability
        _ (println "\n8. Testing undo/redo...")
        _ (assert (log/can-undo?) "Should be able to undo")

        undo-entry (log/undo-entry)
        _ (println (str "   ✓ Undo entry: " (get-in undo-entry [:intent :op])))

        _ (assert (log/can-redo?) "Should be able to redo")
        redo-entry (log/redo-entry)
        _ (println (str "   ✓ Redo entry: " (get-in redo-entry [:intent :op])))

        ;; Final state
        final-db db6

        _ (println "\n=== Final State ===")
        _ (println (str "Nodes: " (count (:nodes final-db))))
        _ (println (str "Cards in deck: " (count (indexes/get-deck-cards final-db deck-id))))
        _ (println (str "Log entries: " (log/log-size)))
        _ (println (str "SRS indexes computed: "
                        (keys (get final-db :derived))))]

    (println "\n✅ All assertions passed!")
    {:db final-db
     :log-size (log/log-size)}))

;; ============================================================================
;; Image Occlusion Demo
;; ============================================================================

(defn demo-image-occlusion
  "Demo: Create image-occlusion card with media children."
  []
  (println "\n=== SRS Demo: Image Occlusion ===\n")

  (log/reset-log!)
  (plugin/init-default-plugins!)

  (let [db0 (fresh-db)
        deck-id "anatomy-deck"

        ;; Create deck
        deck-ops [{:op :create-node
                   :id deck-id
                   :type :deck
                   :props {:name "Anatomy"}}
                  {:op :place
                   :id deck-id
                   :under :decks
                   :at :last}]

        deck-result (interpret/interpret db0 deck-ops)
        db1 (:db deck-result)

        ;; Create image-occlusion card
        _ (println "Creating image-occlusion card with 2 masks...")

        card-intent {:op :srs/create-card
                     :card-id "card-heart"
                     :deck-id deck-id
                     :card-type :image-occlusion
                     :markdown-file "decks/anatomy/heart.md"
                     :front "Identify the highlighted structure"
                     :back "Right ventricle"
                     :props {:image-url "/images/heart.png"
                             :question "Identify the highlighted chamber"
                             :answer "Right ventricle"
                             :occlusions [{:id "occ1"
                                           :shape "M10,10 L50,10 L50,50 Z"}
                                          {:id "occ2"
                                           :shape "M70,20 L100,20 L100,40 Z"}]}}

        {db2 :db} (run-srs-transaction db1 card-intent)

        ;; Assertions: Card + content + 2 media nodes
        _ (let [card-children (get-in db2 [:children-by-parent "card-heart"])
                media-children (filter #(= :media (get-in db2 [:nodes % :type]))
                                       card-children)]
            (assert (= 2 (count media-children))
                    (str "Should have 2 media children, got " (count media-children))))

        _ (println "   ✓ Card created with 2 media nodes (occlusion masks)")

        ;; Verify media index
        _ (let [media (indexes/get-card-media db2 "card-heart")]
            (assert (= 2 (count media))
                    "Media index should have 2 entries"))

        _ (println "   ✓ Media index correctly tracks occlusions")]

    (println "\n✅ Image occlusion demo passed!")))

;; ============================================================================
;; Run All Demos
;; ============================================================================

(defn run-all-demos!
  "Run all demo scenarios with assertions."
  []
  (demo-basic-workflow)
  (demo-image-occlusion)
  (println "\n🎉 All demos completed successfully!"))

(comment
  ;; Run in REPL:
  (run-all-demos!)

  ;; Or run individually:
  (demo-basic-workflow)
  (demo-image-occlusion))

(run-all-demos!)
