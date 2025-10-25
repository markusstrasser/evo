(ns kernel.history
  "Undo/redo infrastructure for event-sourced DB.

   Operates on complete DB snapshots (including :nodes, :derived, :selection, :ui).
   History is stored as `:history` namespace at DB root:
   {:history {:past [] :future [] :limit 50}}

   See ADR-015 for architectural rationale.")

(defn get-history
  "Returns the history map from DB, or empty history if none exists."
  [DB]
  (get DB :history {:past [] :future [] :limit 50}))

(defn can-undo?
  "Returns true if there are past states to undo to."
  [DB]
  (seq (:past (get-history DB))))

(defn can-redo?
  "Returns true if there are future states to redo to."
  [DB]
  (seq (:future (get-history DB))))

(defn undo-count
  "Returns the number of undo steps available."
  [DB]
  (count (:past (get-history DB))))

(defn redo-count
  "Returns the number of redo steps available."
  [DB]
  (count (:future (get-history DB))))

(defn- strip-history
  "Remove :history namespace from DB (for storing in history stack)."
  [DB]
  (dissoc DB :history))

(defn record
  "Record current DB state to undo stack before making a change.
   Clears redo stack (new action invalidates future).
   Trims past stack to stay within limit.

   Returns DB with updated :history."
  [DB]
  (let [{:keys [past limit]} (get-history DB)
        db-snapshot (strip-history DB)
        new-past (conj (vec past) db-snapshot)
        ;; Trim to limit (keep most recent N)
        trimmed-past (if (> (count new-past) limit)
                       (vec (take-last limit new-past))
                       new-past)]
    (assoc DB :history
           {:past trimmed-past
            :future []
            :limit limit})))

(defn undo
  "Restore previous DB state from history.
   Moves current state to future (for redo).
   Returns updated DB, or nil if no history."
  [DB]
  (let [{:keys [past future limit]} (get-history DB)]
    (when (seq past)
      (let [current-snapshot (strip-history DB)
            prev-snapshot (peek past)
            new-past (pop past)
            new-future (conj (vec future) current-snapshot)]
        (assoc prev-snapshot :history
               {:past new-past
                :future new-future
                :limit limit})))))

(defn redo
  "Restore next DB state from future.
   Moves current state to past (for undo).
   Returns updated DB, or nil if no future."
  [DB]
  (let [{:keys [past future limit]} (get-history DB)]
    (when (seq future)
      (let [current-snapshot (strip-history DB)
            next-snapshot (peek future)
            new-future (pop future)
            new-past (conj (vec past) current-snapshot)]
        (assoc next-snapshot :history
               {:past new-past
                :future new-future
                :limit limit})))))

(defn set-limit
  "Set the maximum number of undo steps to keep.
   Trims past stack if current size exceeds new limit.
   Returns DB with updated limit."
  [DB new-limit]
  (let [{:keys [past future]} (get-history DB)
        trimmed-past (if (> (count past) new-limit)
                       (vec (take-last new-limit past))
                       past)]
    (assoc DB :history
           {:past trimmed-past
            :future future
            :limit new-limit})))

(defn clear-history
  "Clear all history (past and future).
   Returns DB with empty history stacks."
  [DB]
  (let [{:keys [limit]} (get-history DB)]
    (assoc DB :history
           {:past []
            :future []
            :limit limit})))
