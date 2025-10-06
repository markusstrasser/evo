(ns lab.srs.log
  "Append-only log for SRS transactions.
   Based on Codex proposal: {:timestamp :intent :compiled-ops :kernel/after-hash}"
  #?(:clj (:require [clojure.java.io :as io]
                    [clojure.edn :as edn])))

;; ============================================================================
;; Log Entry Format
;; ============================================================================

(defn log-entry
  "Create a log entry for an SRS transaction.

   Format:
   {:log/id uuid-string
    :timestamp iso-string
    :intent {:op :srs/... ...}
    :compiled-ops [{:op :create-node ...} ...]
    :kernel/after-hash \"sha256...\"  ; Optional integrity check
    :source :ui | :markdown           ; Where intent originated
    :actor \"user-id\"}"
  [{:keys [intent compiled-ops db-after source actor]}]
  {:log/id #?(:clj (str (java.util.UUID/randomUUID))
              :cljs (str (random-uuid)))
   :timestamp #?(:clj (str (java.time.Instant/now))
                 :cljs (.toISOString (js/Date.)))
   :intent intent
   :compiled-ops compiled-ops
   :kernel/after-hash (when db-after
                        ;; Simple hash for integrity (in prod, use proper hash)
                        (str (hash db-after)))
   :source (or source :ui)
   :actor (or actor :system)})

;; ============================================================================
;; In-Memory Log (for labs demo)
;; ============================================================================

(defonce ^:private log-state
  (atom []))

(defn append!
  "Append an entry to the log.
   Returns the entry with index."
  [entry]
  (let [indexed-entry (assoc entry :log/index (count @log-state))]
    (swap! log-state conj indexed-entry)
    indexed-entry))

(defn get-log
  "Get the entire log or a range."
  ([]
   @log-state)
  ([start-idx end-idx]
   (subvec @log-state start-idx end-idx)))

(defn get-entry
  "Get a single log entry by index."
  [idx]
  (get @log-state idx))

(defn reset-log!
  "Clear the log (for testing)."
  []
  (reset! log-state []))

(defn log-size
  "Get the number of entries in the log."
  []
  (count @log-state))

;; ============================================================================
;; Undo/Redo Support
;; ============================================================================

(defonce ^:private cursor
  (atom nil))

(defn set-cursor!
  "Set the log cursor position."
  [idx]
  (reset! cursor idx))

(defn get-cursor
  "Get current cursor position."
  []
  @cursor)

(defn can-undo?
  "Check if undo is possible."
  []
  (and @cursor (pos? @cursor)))

(defn can-redo?
  "Check if redo is possible."
  []
  (and @cursor (< @cursor (dec (count @log-state)))))

(defn undo-entry
  "Get the entry to undo (move cursor back)."
  []
  (when (can-undo?)
    (let [idx (dec @cursor)
          entry (get-entry idx)]
      (set-cursor! idx)
      entry)))

(defn redo-entry
  "Get the entry to redo (move cursor forward)."
  []
  (when (can-redo?)
    (let [idx (inc @cursor)
          entry (get-entry idx)]
      (set-cursor! idx)
      entry)))

;; ============================================================================
;; Disk Persistence
;; ============================================================================

(def ^:dynamic *log-file* "ops-log.edn")

(defn persist-entry!
  "Append a single log entry to disk as EDN (CLJ only).
   Each entry is written on its own line for easy incremental reads."
  [entry]
  #?(:clj (do (io/make-parents *log-file*)
              (spit *log-file* (str (pr-str entry) "\n") :append true))
     :cljs nil))

(defn load-from-file
  "Load all log entries from EDN file (CLJ only).
   Returns vector of entries in order, or empty vector if file doesn't exist."
  []
  #?(:clj (if (.exists (io/file *log-file*))
            (with-open [r (io/reader *log-file*)]
              (vec (map edn/read-string (line-seq r))))
            [])
     :cljs []))

(defn restore-log!
  "Restore log state from disk file (CLJ only).
   Replaces current in-memory log with entries from file.
   Returns number of entries loaded."
  []
  #?(:clj (let [entries (load-from-file)]
            (reset! log-state entries)
            (when (seq entries)
              (set-cursor! (dec (count entries))))
            (count entries))
     :cljs 0))

;; ============================================================================
;; Transaction Recording
;; ============================================================================

(defn record-transaction!
  "Record a complete SRS transaction to the log.
   Auto-persists to disk (CLJ only).
   Returns the log entry."
  [{:keys [intent compiled-ops _db-before db-after source actor]}]
  (let [entry (log-entry {:intent intent
                          :compiled-ops compiled-ops
                          :db-after db-after
                          :source source
                          :actor actor})]
    (append! entry)
    ;; Update cursor to point to latest
    (set-cursor! (dec (log-size)))
    ;; Persist to disk (CLJ only, CLJS no-op)
    (persist-entry! entry)
    entry))

;; ============================================================================
;; Query Helpers
;; ============================================================================

(defn get-intents-by-card
  "Get all intents affecting a specific card."
  [card-id]
  (->> @log-state
       (filter (fn [entry]
                 (let [intent (:intent entry)]
                   (= card-id (:card-id intent)))))
       vec))

(defn get-recent-reviews
  "Get recent review intents (last N)."
  [n]
  (->> @log-state
       (filter #(= :srs/review-card (get-in % [:intent :op])))
       (take-last n)
       vec))

(defn get-entries-since
  "Get log entries since a timestamp."
  [since-timestamp]
  (->> @log-state
       (filter (fn [entry]
                 (pos? (compare (:timestamp entry) since-timestamp))))
       vec))

;; ============================================================================
;; Persistence Stub (for future implementation)
;; ============================================================================

;; Future: persist-to-file! and load-from-file would write/read EDN to disk
