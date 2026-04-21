(ns kernel.history
  "Pure undo/redo history. Operates on a history value, NOT on db.

   History is a snapshot stack:
     {:past   [[db-snapshot session-snapshot] ...]
      :future [...]
      :limit  50}

   Session state (cursor, selection) is recorded alongside each snapshot
   so undo/redo can restore cursor placement.

   This namespace is pure: callers thread history explicitly. The shell
   owns the storage atom (see shell.history).

   NOTE: This is intentionally a transitional API. Phase B of the kernel
   refactor replaces snapshot history with an append-only op log; this
   namespace will be deleted at that point.")

(def empty-history
  {:past [] :future [] :limit 50})

(defn- session-snapshot
  "Capture the subset of session state relevant for undo/redo restore.
   Transient UI flags (e.g. suppress-blur-exit) are deliberately dropped."
  [session]
  (when session
    {:selection (:selection session)
     :ui (select-keys (:ui session) [:editing-block-id :cursor-position])}))

(defn- trim-past
  "Keep only the last `limit` entries."
  [past limit]
  (let [v (vec past)]
    (if (and (some? limit) (> (count v) limit))
      (vec (take-last limit v))
      v)))

(defn can-undo? [history] (boolean (seq (:past history))))
(defn can-redo? [history] (boolean (seq (:future history))))
(defn undo-count [history] (count (:past history)))
(defn redo-count [history] (count (:future history)))

(defn record
  "Record current (db, session) to the past. Clears redo.
   Returns new history value."
  [history db session]
  (let [{:keys [past limit] :or {limit 50}} history
        entry {:db db :session (session-snapshot session)}
        new-past (trim-past (conj (vec past) entry) limit)]
    {:past new-past :future [] :limit limit}))

(defn undo
  "Rewind one step. Moves current (db, session) to future.
   Returns {:history :db :session} or nil if :past is empty."
  [history db session]
  (let [{:keys [past future limit]} history]
    (when (seq past)
      (let [prev (peek past)
            current {:db db :session (session-snapshot session)}]
        {:history {:past (pop past)
                   :future (conj (vec future) current)
                   :limit limit}
         :db (:db prev)
         :session (:session prev)}))))

(defn redo
  "Advance one step. Moves current (db, session) to past.
   Returns {:history :db :session} or nil if :future is empty."
  [history db session]
  (let [{:keys [past future limit]} history]
    (when (seq future)
      (let [nxt (peek future)
            current {:db db :session (session-snapshot session)}]
        {:history {:past (conj (vec past) current)
                   :future (pop future)
                   :limit limit}
         :db (:db nxt)
         :session (:session nxt)}))))

(defn set-limit
  "Adjust the max number of undo steps, trimming past if necessary."
  [history new-limit]
  {:past (trim-past (:past history) new-limit)
   :future (:future history)
   :limit new-limit})

(defn clear
  "Empty both past and future."
  [history]
  {:past [] :future [] :limit (:limit history)})
