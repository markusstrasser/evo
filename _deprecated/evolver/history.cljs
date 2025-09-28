(ns evolver.history
  "Pure functions for managing snapshot-based undo/redo history.
   The history ring maintains past, present, and future states."
  (:require [malli.core :as m]))

(def history-ring-schema
  "Schema for the history ring structure."
  [:map
   [:past [:vector any?]] ; Vector of previous states
   [:present any?] ; Current state of the application  
   [:future [:vector any?]]]) ; Vector of undone states (for redo)

(defn push-snapshot
  "Takes the current history ring and a new-present state. Pushes the
   current present state into the past and sets the new-present. Clears
   the future, as any new action invalidates the redo stack."
  [{:keys [past present]} new-present-state]
  {:past (conj past present)
   :present new-present-state
   :future []})

(defn undo
  "Moves the present state to the future and pops the most recent
   past state into the present. Returns the history ring unmodified
   if no past states exist."
  [{:keys [past present future] :as history-ring}]
  (if-let [prev-state (peek past)]
    {:past (pop past)
     :present prev-state
     :future (conj future present)}
    history-ring))

(defn redo
  "Moves the present state to the past and pops the most recent
   future state into the present. Returns the history ring unmodified
   if no future states exist."
  [{:keys [past present future] :as history-ring}]
  (if-let [next-state (first future)]
    {:past (conj past present)
     :present next-state
     :future (subvec future 1)}
    history-ring))

(defn can-undo?
  "Returns true if there are past states available for undo."
  [{:keys [past]}]
  (seq past))

(defn can-redo?
  "Returns true if there are future states available for redo."
  [{:keys [future]}]
  (seq future))

(defn create-history-ring
  "Creates a new history ring with the given initial state."
  [initial-state]
  {:past []
   :present initial-state
   :future []})

(defn validate-history-ring
  "Validates that the history ring conforms to the expected schema."
  [history-ring]
  (m/validate history-ring-schema history-ring))