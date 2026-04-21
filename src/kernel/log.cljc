(ns kernel.log
  "Append-only op log. The current db is a pure function of the log.

   Replaces the old snapshot-based kernel.history. Each entry records one
   user-level transaction: the intent, the kernel ops it produced, the
   pre-dispatch session snapshot (for cursor restore on undo), plus audit
   fields (op-id, prev-op-id, timestamp).

   State shape:
     {:root-db <db>        ; base db; ops are folded on top
      :ops     [<entry>]   ; linear, branch-pruned history
      :head    <int>       ; index of current head (-1 when empty)
      :limit   <int>}      ; max retained ops (defaults 50)

   Entry shape:
     {:op-id          uuid
      :prev-op-id     uuid | nil
      :timestamp      ms
      :intent         {...}             ; user-level intent
      :ops            [{...}]           ; three-op kernel primitives
      :session-before {...} | nil}      ; subset for undo cursor restore

   This namespace is pure — the shell owns the !log atom (see shell.log)
   and is responsible for keeping a derived !db in sync.

   Deferred (Phase B follow-ups, described in the refactor plan §4):
     - branch-aware checkpoint store
     - LRU cache of checkpoints
     - on-disk checkpoint persistence
     - head-db memoization

   The lean first cut re-folds from :root-db on every undo/redo. For
   evo's current scale (< a few hundred ops per session) this is
   sub-millisecond. Revisit when profile shows need."
  (:require [kernel.db :as db]
            [kernel.transaction :as tx]))

(def ^:const default-limit 50)

(def empty-log
  {:root-db (db/empty-db)
   :ops []
   :head -1
   :limit default-limit})

(defn- session-snapshot
  "Subset of session to capture for undo cursor restore."
  [session]
  (when session
    {:selection (:selection session)
     :ui (select-keys (:ui session) [:editing-block-id :cursor-position])}))

(defn make-entry
  "Build a log entry from a dispatch result. Caller supplies a freshly
   minted op-id and the current timestamp (the kernel never reads a clock
   or mints entropy — see invariants §3.3)."
  [{:keys [op-id prev-op-id timestamp intent ops session]}]
  {:op-id op-id
   :prev-op-id prev-op-id
   :timestamp timestamp
   :intent intent
   :ops (vec ops)
   :session-before (session-snapshot session)})

(defn- trim-to-limit
  "Enforce :limit by dropping oldest entries; keep :head pointing at the
   same logical entry by also re-folding :root-db forward."
  [log]
  (let [{:keys [root-db ops head limit]} log
        n (count ops)]
    (if (and limit (> n limit))
      (let [drop-n (- n limit)
            absorbed (subvec ops 0 drop-n)
            remaining (subvec ops drop-n)
            new-root (reduce (fn [d entry]
                               (:db (tx/interpret d (:ops entry))))
                             root-db
                             absorbed)]
        {:root-db new-root
         :ops (vec remaining)
         :head (- head drop-n)
         :limit limit})
      log)))

(defn can-undo? [log] (>= (:head log) 0))
(defn can-redo? [log] (< (:head log) (dec (count (:ops log)))))

(defn undo-count [log] (inc (:head log)))
(defn redo-count [log] (- (count (:ops log)) (inc (:head log))))

(defn append
  "Append an entry after head, pruning any orphaned future branch.

   Returns the new log. If the appended window exceeds :limit, the
   oldest entries are absorbed into :root-db."
  [log entry]
  (let [{:keys [root-db ops head limit]} log
        kept (subvec (vec ops) 0 (inc head))   ; prune future branch
        new-ops (conj kept entry)]
    (trim-to-limit {:root-db root-db
                    :ops new-ops
                    :head (dec (count new-ops))
                    :limit limit})))

(defn undo
  "Rewind head by one. Returns new log or nil if nothing to undo."
  [log]
  (when (can-undo? log)
    (update log :head dec)))

(defn redo
  "Advance head by one. Returns new log or nil if nothing to redo."
  [log]
  (when (can-redo? log)
    (update log :head inc)))

(defn entry-at-head
  "Return the entry at the current head, or nil when at root."
  [log]
  (let [{:keys [ops head]} log]
    (when (>= head 0)
      (nth ops head))))

(defn previous-entry
  "Return the entry immediately before head (i.e. the one an undo would
   step past), or nil if head is at root."
  [log]
  (let [{:keys [ops head]} log]
    (when (>= head 0)
      (nth ops head))))

(defn head-db
  "Fold ops[0..head] onto :root-db. Returns the current db value."
  [log]
  (let [{:keys [root-db ops head]} log
        applied (take (inc head) ops)]
    (reduce (fn [d entry]
              (:db (tx/interpret d (:ops entry))))
            root-db
            applied)))

(defn reset-root
  "Build a fresh log whose root is `db` and whose history is empty.
   Used on folder load and test reset."
  ([db] (reset-root db default-limit))
  ([db limit]
   {:root-db db :ops [] :head -1 :limit limit}))

(defn set-limit
  "Adjust :limit, absorbing older entries into :root-db if necessary."
  [log new-limit]
  (trim-to-limit (assoc log :limit new-limit)))

(defn clear
  "Drop all ops, retaining :root-db and :limit."
  [log]
  (assoc log :ops [] :head -1))
