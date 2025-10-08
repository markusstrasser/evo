(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]))

;; Card parsing

(defn parse-qa-card
  "Parse a QA card in the format 'Question ; Answer'"
  [text]
  (when-let [[_ question answer] (re-matches #"^(.+?)\s*;\s*(.+)$" text)]
    {:type :qa
     :question (str/trim question)
     :answer (str/trim answer)}))

(defn parse-cloze-card
  "Parse a cloze deletion card in the format 'Text [deletion] more text'"
  [text]
  (when-let [matches (re-seq #"\[([^\]]+)\]" text)]
    (let [deletions (mapv second matches)]
      {:type :cloze
       :template text
       :deletions deletions})))

(defn parse-card
  "Parse a card from markdown text. Returns nil if invalid."
  [text]
  (let [trimmed (str/trim text)]
    (or (parse-qa-card trimmed)
        (parse-cloze-card trimmed))))

;; Card hashing

(defn card-hash
  "Generate a hash for a card based on its content"
  [card-data]
  #?(:clj  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                 bs (.getBytes (pr-str card-data) "UTF-8")]
             (.update digest bs)
             (format "%064x" (BigInteger. 1 (.digest digest))))
     :cljs (let [s (pr-str card-data)]
             ;; Simple hash for CLJS (good enough for this use case)
             (str (cljs.core/hash s)))))

;; Card metadata

(defn new-card-meta
  "Create metadata for a new card"
  [hash]
  {:card-hash hash
   :created-at #?(:clj (java.util.Date.) :cljs (js/Date.))
   :due-at #?(:clj (java.util.Date.) :cljs (js/Date.))
   :interval 0
   :ease-factor 2.5
   :reviews 0})

;; Scheduling (mock algorithm for testing)

(defn schedule-card
  "Schedule next review based on rating. Rating: :forgot :hard :good :easy
   Mock algorithm: just increments review count and sets a fixed interval."
  [card-meta rating]
  (let [new-meta (update card-meta :reviews inc)
        now #?(:clj (System/currentTimeMillis) :cljs (.getTime (js/Date.)))
        ;; Mock intervals: forgot=now, hard=1min, good=5min, easy=10min
        interval-ms (case rating
                      :forgot 0
                      :hard 60000
                      :good 300000
                      :easy 600000)
        due-ms (+ now interval-ms)]
    (assoc new-meta
           :due-at #?(:clj (java.util.Date. due-ms) :cljs (js/Date. due-ms))
           :last-rating rating)))

;; Event log

(defn new-event
  "Create a new event"
  [event-type data]
  {:event/type event-type
   :event/timestamp #?(:clj (java.util.Date.) :cljs (js/Date.))
   :event/data data})

(defn review-event
  "Create a review event"
  [hash rating]
  (new-event :review {:card-hash hash
                      :rating rating}))

(defn card-created-event
  "Create a card-created event"
  [hash card-data]
  (new-event :card-created {:card-hash hash
                            :card card-data}))

;; State reduction

(defn apply-event
  "Apply an event to the current state"
  [state event]
  (case (:event/type event)
    :card-created
    (let [{:keys [card-hash card]} (:event/data event)]
      (-> state
          (assoc-in [:cards card-hash] card)
          (assoc-in [:meta card-hash] (new-card-meta card-hash))))

    :review
    (let [{:keys [card-hash rating]} (:event/data event)
          current-meta (get-in state [:meta card-hash])]
      (if current-meta
        (assoc-in state [:meta card-hash] (schedule-card current-meta rating))
        state))

    ;; Unknown event type - ignore
    state))

(defn reduce-events
  "Reduce a sequence of events to produce the current state"
  [events]
  (reduce apply-event
          {:cards {}
           :meta {}
           :log []}
          events))

;; Query helpers

(defn due-cards
  "Get all cards that are due for review"
  [state]
  (let [now #?(:clj (System/currentTimeMillis) :cljs (.getTime (js/Date.)))]
    (->> (:meta state)
         (filter (fn [[_hash meta]]
                   (<= #?(:clj (.getTime (:due-at meta))
                          :cljs (.getTime (:due-at meta)))
                       now)))
         (map first)
         vec)))

(defn card-with-meta
  "Get card data with its metadata"
  [state hash]
  (when-let [card (get-in state [:cards hash])]
    (assoc card :meta (get-in state [:meta hash]))))
