(ns anki-fixtures
  "REPL test fixtures for common Anki review scenarios.

   Scope: Anki-specific testing (cards, reviews, scheduling, undo/redo).
   Use: Testing lab.anki.core, review workflows, state management.

   For generic kernel fixtures (nodes, trees, db shapes), see:
   - fixtures.cljc (Generic test utilities)

   This file provides:
   - Card generation (generate-cards)
   - Review session fixtures (review-session-fixture)
   - Undo/redo scenarios (undo-redo-fixture)
   - State inspection (print-state-summary, print-event-log)"
  (:require [lab.anki.core :as core]))

(defn generate-cards
  "Generate n test cards with sequential IDs"
  [n]
  (mapv (fn [i]
          (core/card-created-event
           (str "card" (inc i))
           {:type :qa
            :question (str "Question " (inc i))
            :answer (str "Answer " (inc i))}))
        (range n)))

(defn generate-reviews
  "Generate review events for card IDs with given ratings"
  [card-ids ratings]
  (mapv (fn [card-id rating]
          (core/review-event card-id rating))
        card-ids
        ratings))

(defn review-session-fixture
  "Generate a typical review session for testing.
  
  Returns map with:
    :cards-events - card creation events
    :review-events - review events
    :all-events - combined event log
    :state - current state after all events"
  [n-cards reviews]
  (let [card-events (generate-cards n-cards)
        card-ids (mapv #(get-in % [:event/data :card-hash]) card-events)
        review-events (mapv (fn [[card-idx rating]]
                              (core/review-event (nth card-ids card-idx) rating))
                            reviews)
        all-events (concat card-events review-events)
        state (core/reduce-events all-events)]
    {:cards-events card-events
     :review-events review-events
     :all-events (vec all-events)
     :state state
     :card-ids card-ids}))

(defn undo-redo-fixture
  "Generate scenario with undo/redo events.
  
  Example:
    (undo-redo-fixture 3 [[0 :good] [1 :easy]] [:undo :undo :redo])"
  [n-cards reviews undo-redo-actions]
  (let [{:keys [all-events state card-ids]} (review-session-fixture n-cards reviews)
        actions (reduce
                 (fn [{:keys [events state]} action]
                   (let [new-event (case action
                                     :undo (when-let [target (last (:undo-stack state))]
                                             (core/undo-event target))
                                     :redo (when-let [target (last (:redo-stack state))]
                                             (core/redo-event target))
                                     nil)]
                     (if new-event
                       (let [new-events (conj events new-event)
                             new-state (core/reduce-events new-events)]
                         {:events new-events :state new-state})
                       {:events events :state state})))
                 {:events all-events :state state}
                 undo-redo-actions)]
    (merge actions {:card-ids card-ids})))

(defn print-state-summary
  "Print human-readable summary of state"
  [state]
  (let [due (core/due-cards state)]
    (println "=== State Summary ===")
    (println "Total cards:" (count (:cards state)))
    (println "Due cards:" (count due))
    (println "Due card IDs:" due)
    (println "Undo stack size:" (count (:undo-stack state)))
    (println "Redo stack size:" (count (:redo-stack state)))
    (println "Can undo?" (boolean (seq (:undo-stack state))))
    (println "Can redo?" (boolean (seq (:redo-stack state))))))

(defn print-event-log
  "Print human-readable event log"
  [events]
  (println "=== Event Log ===")
  (doseq [[idx event] (map-indexed vector events)]
    (let [type (:event/type event)
          data (:event/data event)]
      (case type
        :card-created
        (println (str idx ": CREATE card " (:card-hash data)))

        :review
        (println (str idx ": REVIEW card " (:card-hash data) " → " (:rating data)))

        :undo
        (println (str idx ": UNDO event " (:target-event-id data)))

        :redo
        (println (str idx ": REDO event " (:target-event-id data)))

        (println (str idx ": " type))))))

(comment
  ;; Example usage in REPL

  ;; Basic session with 3 cards, 2 reviews
  (def session (review-session-fixture 3 [[0 :good] [1 :easy]]))
  (print-state-summary (:state session))

  ;; Session with undo/redo
  (def undo-session (undo-redo-fixture 3 [[0 :good] [1 :easy]] [:undo :undo :redo]))
  (print-state-summary (:state undo-session))
  (print-event-log (:events undo-session))

  ;; Test undo behavior
  (let [{:keys [state]} (review-session-fixture 5 [[0 :good] [1 :easy] [2 :hard]])]
    (println "Undo stack contains only reviews:")
    (println "  Stack size:" (count (:undo-stack state)))
    (println "  Expected: 3 (only review events)")))
