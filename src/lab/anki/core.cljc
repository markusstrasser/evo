(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]
            [medley.core :as m]))

;; Card parsing

(def cloze-pattern #"\[([^\]]+)\]")
(def image-occlusion-pattern #"^!\[(.+?)\]\((.+?)\)\s*\{(.+?)\}$")

(defn parse-qa-multiline
  "Parse QA card from consecutive q/a lines"
  [lines]
  (loop [remaining lines
         question nil
         answer nil]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (str/starts-with? trimmed "q ")
          (recur (rest remaining) (subs trimmed 2) answer)

          (str/starts-with? trimmed "a ")
          (recur (rest remaining) question (subs trimmed 2))

          :else
          (recur (rest remaining) question answer)))
      (when (and question answer)
        {:question (str/trim question)
         :answer (str/trim answer)}))))

(def card-parsers
  "Registry of card parsers - add new card types here.
   Order matters: more specific patterns should come first."
  [{:type :image-occlusion
    :parse (fn [text]
             (when-let [[_ alt-text image-url regions] (re-matches image-occlusion-pattern text)]
               {:alt-text (str/trim alt-text)
                :image-url (str/trim image-url)
                :regions (str/split regions #",\s*")}))}
   {:type :cloze
    :parse (fn [text]
             (let [trimmed (str/trim text)]
               ;; Support both formats:
               ;; 1. "c [cloze] text" (new format with prefix)
               ;; 2. "Direct [cloze] text" (old format, no prefix)
               (let [content (if (str/starts-with? trimmed "c ")
                               (subs trimmed 2)
                               trimmed)]
                 (when-let [matches (re-seq cloze-pattern content)]
                   {:template content
                    :deletions (mapv second matches)}))))}
   {:type :qa
    :parse (fn [text]
             (let [trimmed (str/trim text)]
               ;; Try old format first: "Question ; Answer"
               (if (str/includes? trimmed " ; ")
                 (let [[question answer] (str/split trimmed #"\s*;\s*" 2)]
                   (when (and question answer)
                     {:question (str/trim question)
                      :answer (str/trim answer)}))
                 ;; Fall back to new format: "q Question\na Answer"
                 (parse-qa-multiline (str/split-lines text)))))}])

(defn parse-card
  "Parse a card from markdown text. Returns nil if invalid."
  [text]
  (let [trimmed (str/trim text)]
    (some (fn [{card-type :type parse-fn :parse}]
            (when-let [data (parse-fn trimmed)]
              (assoc data :type card-type)))
          card-parsers)))

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

;; Time helpers

(defn now-ms
  "Get current time in milliseconds"
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn date-from-ms
  "Create date from milliseconds"
  [ms]
  #?(:clj (java.util.Date. ms)
     :cljs (js/Date. ms)))

;; Card metadata

(defn new-card-meta
  "Create metadata for a new card"
  [h]
  {:card-hash h
   :created-at (date-from-ms (now-ms))
   :due-at (date-from-ms (now-ms))
   :reviews 0})

;; Scheduling (mock algorithm for testing)

(def rating->interval-ms
  "Mock SRS intervals in milliseconds for each rating"
  {:forgot 0
   :hard 60000
   :good 300000
   :easy 600000})

(defn schedule-card
  "Schedule next review based on rating. Rating: :forgot :hard :good :easy
   Mock algorithm: increments review count and sets a fixed interval."
  [card-meta rating]
  (-> card-meta
      (update :reviews inc)
      (assoc :due-at (date-from-ms (+ (now-ms) (get rating->interval-ms rating 0)))
             :last-rating rating)))

;; Event log

(defn new-event
  "Create a new event"
  [event-type data]
  {:event/type event-type
   :event/timestamp (date-from-ms (now-ms))
   :event/data data})

(defn review-event
  "Create a review event"
  [h rating]
  (new-event :review {:card-hash h
                      :rating rating}))

(defn card-created-event
  "Create a card-created event"
  [h card-data]
  (new-event :card-created {:card-hash h
                            :card card-data}))

;; Image occlusion helpers

(defn expand-image-occlusion
  "Expand an image-occlusion card into virtual child cards for each occlusion.
   Returns a map of {child-hash virtual-card-data}."
  [parent-hash card]
  (when (= :image-occlusion (:type card))
    (let [occlusions (:occlusions card)]
      (reduce (fn [acc occlusion]
                (let [child-id [parent-hash (:oid occlusion)]
                      child-hash (card-hash child-id)]
                  (assoc acc child-hash
                         {:type :image-occlusion/item
                          :parent-id parent-hash
                          :occlusion-oid (:oid occlusion)
                          :asset (:asset card)
                          :prompt (:prompt card)
                          :shape (:shape occlusion)
                          :answer (:answer occlusion)})))
              {}
              occlusions))))

;; State reduction

(defn apply-event
  "Apply an event to the current state"
  [state event]
  (case (:event/type event)
    :card-created
    (let [{h :card-hash card :card} (:event/data event)]
      (if (= :image-occlusion (:type card))
        ;; Store parent and expand into virtual children
        (let [children (expand-image-occlusion h card)]
          (reduce (fn [s [child-hash child-card]]
                    (-> s
                        (assoc-in [:cards child-hash] child-card)
                        (assoc-in [:meta child-hash] (new-card-meta child-hash))))
                  (assoc-in state [:cards h] card)
                  children))
        ;; Regular card
        (-> state
            (assoc-in [:cards h] card)
            (assoc-in [:meta h] (new-card-meta h)))))

    :review
    (let [{h :card-hash rating :rating} (:event/data event)
          card-meta (get-in state [:meta h])]
      (if card-meta
        (assoc-in state [:meta h] (schedule-card card-meta rating))
        state))

    ;; Unknown event type - ignore
    state))

(defn reduce-events
  "Reduce a sequence of events to produce the current state"
  [events]
  (reduce apply-event
          {:cards {}
           :meta {}}
          events))

;; Query helpers

(defn due-cards
  "Get all cards that are due for review"
  [state]
  (let [now (now-ms)]
    (->> (:meta state)
         (m/filter-vals (fn [card-meta]
                          (<= (.getTime (:due-at card-meta)) now)))
         keys
         vec)))

(defn card-with-meta
  "Get card data with its metadata"
  [state h]
  (when-let [card (get-in state [:cards h])]
    (assoc card :meta (get-in state [:meta h]))))
