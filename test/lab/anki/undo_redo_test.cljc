(ns lab.anki.undo-redo-test
  (:require [clojure.test :refer [deftest testing is]]
            [lab.anki.core :as core]))

(deftest undo-stack-only-contains-reviews
  (testing "Card creation is not undoable"
    (let [card1 (core/card-created-event "c1" {:type :qa :question "Q1" :answer "A1"})
          card2 (core/card-created-event "c2" {:type :qa :question "Q2" :answer "A2"})
          review1 (core/review-event "c1" :good)
          review2 (core/review-event "c2" :easy)
          events [card1 card2 review1 review2]
          state (core/reduce-events events)]
      (is (= 2 (count (:undo-stack state)))
          "Undo stack should only contain review events, not card-created")
      (is (= 0 (count (:redo-stack state)))
          "Redo stack should be empty initially"))))

(deftest undo-redo-cycle
  (testing "Undo and redo restore state correctly"
    (let [card1 (core/card-created-event "c1" {:type :qa :question "Q1" :answer "A1"})
          review1 (core/review-event "c1" :good)
          events [card1 review1]
          initial-state (-> (core/reduce-events events)
                            (assoc :cards {"c1" {:type :qa :question "Q1" :answer "A1"}}))

          ;; Undo the review
          undo-event (core/undo-event (last (:undo-stack initial-state)))
          after-undo (-> (core/reduce-events (conj events undo-event))
                         (assoc :cards {"c1" {:type :qa :question "Q1" :answer "A1"}}))

          ;; Redo the review
          redo-event (core/redo-event (last (:redo-stack after-undo)))
          after-redo (-> (core/reduce-events (conj (conj events undo-event) redo-event))
                         (assoc :cards {"c1" {:type :qa :question "Q1" :answer "A1"}}))]

      ;; After undo
      (is (= 0 (count (:undo-stack after-undo)))
          "Undo stack should be empty after undoing only review")
      (is (= 1 (count (:redo-stack after-undo)))
          "Redo stack should have the undone review")
      (is (= ["c1"] (core/due-cards after-undo))
          "Card should be due again after undo")

      ;; After redo
      (is (= 1 (count (:undo-stack after-redo)))
          "Undo stack should have review back after redo")
      (is (= 0 (count (:redo-stack after-redo)))
          "Redo stack should be empty after redo")
      (is (= [] (core/due-cards after-redo))
          "Card should not be due after redo"))))

(deftest new-action-clears-redo-stack
  (testing "New review after undo clears redo stack"
    (let [card1 (core/card-created-event "c1" {:type :qa :question "Q1" :answer "A1"})
          review1 (core/review-event "c1" :good)
          events [card1 review1]
          initial-state (core/reduce-events events)

          ;; Undo the review
          undo-event (core/undo-event (last (:undo-stack initial-state)))
          after-undo-events (conj events undo-event)
          after-undo (core/reduce-events after-undo-events)

          ;; Rate card again with different rating
          review2 (core/review-event "c1" :easy)
          after-new-action (core/reduce-events (conj after-undo-events review2))]

      (is (= 1 (count (:redo-stack after-undo)))
          "Redo stack should have undone review")
      (is (= 0 (count (:redo-stack after-new-action)))
          "Redo stack should be cleared after new review")
      (is (= 1 (count (:undo-stack after-new-action)))
          "Undo stack should have new review only"))))

(deftest multiple-undos
  (testing "Multiple undos work correctly"
    (let [card1 (core/card-created-event "c1" {:type :qa :question "Q1" :answer "A1"})
          card2 (core/card-created-event "c2" {:type :qa :question "Q2" :answer "A2"})
          card3 (core/card-created-event "c3" {:type :qa :question "Q3" :answer "A3"})
          review1 (core/review-event "c1" :good)
          review2 (core/review-event "c2" :easy)
          events [card1 card2 card3 review1 review2]
          cards {"c1" {:type :qa :question "Q1" :answer "A1"}
                 "c2" {:type :qa :question "Q2" :answer "A2"}
                 "c3" {:type :qa :question "Q3" :answer "A3"}}
          initial-state (-> (core/reduce-events events)
                            (assoc :cards cards))

          ;; First undo
          undo1 (core/undo-event (last (:undo-stack initial-state)))
          after-undo1 (-> (core/reduce-events (conj events undo1))
                          (assoc :cards cards))

          ;; Second undo
          undo2 (core/undo-event (last (:undo-stack after-undo1)))
          after-undo2 (-> (core/reduce-events (conj (conj events undo1) undo2))
                          (assoc :cards cards))]

      (is (= 2 (count (:undo-stack initial-state)))
          "Initially 2 reviews are undoable")
      (is (= 1 (count (:undo-stack after-undo1)))
          "After first undo, 1 review remains undoable")
      (is (= 0 (count (:undo-stack after-undo2)))
          "After second undo, no reviews remain undoable")
      (is (= 2 (count (:redo-stack after-undo2)))
          "Both reviews should be in redo stack")
      (is (= ["c1" "c2" "c3"] (core/due-cards after-undo2))
          "All cards should be due after undoing all reviews"))))

(deftest undo-card-becomes-due
  (testing "Undone card becomes due again"
    (let [card1 (core/card-created-event "c1" {:type :qa :question "Q1" :answer "A1"})
          review1 (core/review-event "c1" :good)
          events [card1 review1]
          cards {"c1" {:type :qa :question "Q1" :answer "A1"}}
          initial-state (-> (core/reduce-events events)
                            (assoc :cards cards))

          undo-event (core/undo-event (last (:undo-stack initial-state)))
          after-undo (-> (core/reduce-events (conj events undo-event))
                         (assoc :cards cards))]

      (is (= [] (core/due-cards initial-state))
          "Card should not be due after review")
      (is (= ["c1"] (core/due-cards after-undo))
          "Card should be due again after undo"))))