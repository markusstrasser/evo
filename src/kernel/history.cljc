(ns kernel.history
  "Undo/redo infrastructure for event-sourced DB.

   Operates on complete DB snapshots (including :nodes, :derived).
   History is stored as `:history` namespace at DB root:
   {:history {:past [] :future [] :limit 50}}

   Session state (cursor, selection) is stored alongside each DB snapshot
   to enable proper cursor/selection restore on undo/redo.
   Entry format: {:db db-snapshot :session session-snapshot}

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

(defn- trim-to-limit
  "Keep only the last N items from collection, enforcing the history limit.
   Returns a vector."
  [limit coll]
  (let [v (vec coll)
        n (count v)]
    (if (and (some? limit) (> n limit))
      (vec (take-last limit v))
      v)))

(defn- strip-history
  "Remove :history from DB before storing in history stack."
  [DB]
  (dissoc DB :history))

(defn record
  "Record current DB state to undo stack before making a change.
   Clears redo stack (new action invalidates future).
   Trims past stack to stay within limit.

   When session is provided, stores it alongside DB for cursor/selection restore.
   Entry format: {:db db-snapshot :session session-snapshot}

   Returns DB with updated :history."
  ([DB] (record DB nil))
  ([DB session]
   (let [{:keys [past limit]} (get-history DB)
         db-snapshot (strip-history DB)
         ;; Store session subset relevant for undo (cursor position, selection)
         ;; Don't store transient UI state like suppress-blur-exit
         session-snapshot (when session
                            {:selection (:selection session)
                             :ui (select-keys (:ui session)
                                              [:editing-block-id :cursor-position])})
         entry {:db db-snapshot :session session-snapshot}
         new-past (conj (vec past) entry)
         trimmed-past (trim-to-limit limit new-past)]
     (assoc DB :history
            {:past trimmed-past
             :future []
             :limit limit}))))

(defn undo
  "Restore previous DB state from history.
   Moves current state to future (for redo).

   When session is provided, it's stored in future for redo.

   Returns {:db restored-db :session restored-session} or nil if no history.
   Session may be nil if not captured during record."
  ([DB] (undo DB nil))
  ([DB current-session]
   (let [{:keys [past future limit]} (get-history DB)]
     (when (seq past)
       (let [current-db-snapshot (strip-history DB)
             ;; Store current session for redo
             current-session-snapshot (when current-session
                                        {:selection (:selection current-session)
                                         :ui (select-keys (:ui current-session)
                                                          [:editing-block-id :cursor-position])})
             current-entry {:db current-db-snapshot :session current-session-snapshot}
             prev-entry (peek past)
             ;; Handle both old format (just db) and new format ({:db :session})
             prev-db (if (map? prev-entry)
                       (or (:db prev-entry) prev-entry)
                       prev-entry)
             prev-session (when (map? prev-entry) (:session prev-entry))
             new-past (pop past)
             new-future (conj (vec future) current-entry)]
         {:db (-> prev-db
                  (assoc :history {:past new-past
                                   :future new-future
                                   :limit limit}))
          :session prev-session})))))

(defn redo
  "Restore next DB state from future.
   Moves current state to past (for undo).

   When session is provided, it's stored in past for undo.

   Returns {:db restored-db :session restored-session} or nil if no future.
   Session may be nil if not captured during record."
  ([DB] (redo DB nil))
  ([DB current-session]
   (let [{:keys [past future limit]} (get-history DB)]
     (when (seq future)
       (let [current-db-snapshot (strip-history DB)
             ;; Store current session for undo
             current-session-snapshot (when current-session
                                        {:selection (:selection current-session)
                                         :ui (select-keys (:ui current-session)
                                                          [:editing-block-id :cursor-position])})
             current-entry {:db current-db-snapshot :session current-session-snapshot}
             next-entry (peek future)
             ;; Handle both old format (just db) and new format ({:db :session})
             next-db (if (map? next-entry)
                       (or (:db next-entry) next-entry)
                       next-entry)
             next-session (when (map? next-entry) (:session next-entry))
             new-future (pop future)
             new-past (conj (vec past) current-entry)]
         {:db (-> next-db
                  (assoc :history {:past new-past
                                   :future new-future
                                   :limit limit}))
          :session next-session})))))

(defn set-limit
  "Set the maximum number of undo steps to keep.
   Trims past stack if current size exceeds new limit.
   Returns DB with updated limit."
  [DB new-limit]
  (let [{:keys [past future]} (get-history DB)
        trimmed-past (trim-to-limit new-limit past)]
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
