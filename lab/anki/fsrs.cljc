(ns lab.anki.fsrs
  "FSRS (Free Spaced Repetition Scheduler) algorithm implementation
   Ported from hashcards: https://github.com/eudoxia0/hashcards

   The FSRS algorithm tracks:
   - Stability (S): How long a memory lasts
   - Difficulty (D): How hard the card is (1.0 to 10.0)
   - Interval: Days until next review

   Based on: https://github.com/open-spaced-repetition/fsrs4anki")

;; FSRS Parameters (optimized weights)
(def W
  "FSRS model weights - 19 parameters optimized for SRS"
  [0.40255 1.18385 3.173 15.69105 7.1949 0.5345 1.4604 0.0046 1.54575 0.1192 1.01925
   1.9395 0.11 0.29605 2.2698 0.2315 2.9898 0.51655 0.6621])

;; Constants
(def ^:private F (/ 19.0 81.0))
(def ^:private C -0.5)
(def ^:private DESIRED-RETENTION 0.9) ; Default desired retention rate

;; Type aliases (for documentation)
(comment
  (def Recall double)      ; Retrievability: probability of recall (0.0 to 1.0)
  (def Stability double)   ; Stability: how long memory lasts (in days)
  (def Difficulty double)  ; Difficulty: how hard the card is (1.0 to 10.0)
  (def Interval double))   ; Interval: days until next review

;; Core FSRS Functions

(defn retrievability
  "Calculate retrievability (recall probability) based on time elapsed and stability.
   t: time elapsed since last review (in days)
   s: current stability (in days)
   Returns: probability of recall (0.0 to 1.0)"
  [t s]
  (Math/pow (+ 1.0 (* F (/ t s))) C))

(defn interval
  "Calculate optimal interval to achieve desired retention.
   r-d: desired retention rate (typically 0.9)
   s: current stability (in days)
   Returns: optimal interval in days"
  [r-d s]
  (* (/ s F) (- (Math/pow r-d (/ 1.0 C)) 1.0)))

(defn initial-stability
  "Get initial stability based on first grade.
   grade: :forgot :hard :good :easy
   Returns: initial stability in days"
  [grade]
  (case grade
    :forgot (nth W 0)
    :hard   (nth W 1)
    :good   (nth W 2)
    :easy   (nth W 3)))

(defn- clamp-difficulty
  "Clamp difficulty to valid range [1.0, 10.0]"
  [d]
  (max 1.0 (min 10.0 d)))

(defn initial-difficulty
  "Get initial difficulty based on first grade.
   grade: :forgot :hard :good :easy
   Returns: initial difficulty (1.0 to 10.0)"
  [grade]
  (let [g (case grade
            :forgot 1.0
            :hard   2.0
            :good   3.0
            :easy   4.0)]
    (clamp-difficulty
     (+ (- (nth W 4) (Math/exp (* (nth W 5) (- g 1.0)))) 1.0))))

(defn- delta-d
  "Calculate difficulty change based on grade"
  [grade]
  (let [g (case grade
            :forgot 1.0
            :hard   2.0
            :good   3.0
            :easy   4.0)]
    (* (- (nth W 6)) (- g 3.0))))

(defn- dp
  "Calculate intermediate difficulty"
  [d grade]
  (+ d (* (delta-d grade) (/ (- 10.0 d) 9.0))))

(defn new-difficulty
  "Calculate new difficulty after a review.
   d: current difficulty
   grade: review grade
   Returns: new difficulty (1.0 to 10.0)"
  [d grade]
  (clamp-difficulty
   (+ (* (nth W 7) (initial-difficulty :easy))
      (* (- 1.0 (nth W 7)) (dp d grade)))))

(defn- s-success
  "Calculate new stability for successful reviews (Hard/Good/Easy)"
  [d s r grade]
  (let [t-d (- 11.0 d)
        t-s (Math/pow s (- (nth W 9)))
        t-r (- (Math/exp (* (nth W 10) (- 1.0 r))) 1.0)
        h (if (= grade :hard) (nth W 15) 1.0)
        b (if (= grade :easy) (nth W 16) 1.0)
        c (Math/exp (nth W 8))
        alpha (* (+ 1.0 (* t-d t-s t-r h b c)))]
    (* s alpha)))

(defn- s-fail
  "Calculate new stability for failed reviews (Forgot)"
  [d s r]
  (let [d-f (Math/pow d (- (nth W 12)))
        s-f (- (Math/pow (+ s 1.0) (nth W 13)) 1.0)
        r-f (Math/exp (* (nth W 14) (- 1.0 r)))
        c-f (nth W 11)
        s-f (* d-f s-f r-f c-f)]
    (min s-f s)))

(defn new-stability
  "Calculate new stability after a review.
   d: current difficulty
   s: current stability
   r: retrievability at time of review
   grade: review grade
   Returns: new stability in days"
  [d s r grade]
  (if (= grade :forgot)
    (s-fail d s r)
    (s-success d s r grade)))

;; High-level API

(defn schedule-next-review
  "Calculate next review parameters based on current state and grade.

   For first review (no current state):
   {:grade :good} => {:stability 3.17 :difficulty 5.28 :interval 3.0 :due-at <date>}

   For subsequent reviews:
   {:stability 3.17 :difficulty 5.28 :last-review-ms <timestamp> :grade :good}
   => {:stability 10.73 :difficulty 5.27 :interval 11.0 :due-at <date>}

   Parameters:
   - stability: current stability (days) - omit for first review
   - difficulty: current difficulty (1-10) - omit for first review
   - last-review-ms: timestamp of last review - omit for first review
   - grade: review grade (:forgot :hard :good :easy)
   - review-time-ms: timestamp of this review (defaults to now)
   - desired-retention: target retention rate (default 0.9)

   Returns map with:
   - stability: new stability (days)
   - difficulty: new difficulty (1-10)
   - interval: interval to next review (days)
   - interval-ms: interval in milliseconds
   - due-at: Date object for next review"
  [{:keys [stability difficulty last-review-ms grade review-time-ms desired-retention]
    :or {review-time-ms #?(:clj (System/currentTimeMillis)
                          :cljs (.getTime (js/Date.)))
         desired-retention DESIRED-RETENTION}}]

  (let [;; First review or subsequent?
        first-review? (nil? stability)

        ;; Calculate new parameters
        new-s (if first-review?
                (initial-stability grade)
                (let [;; Calculate elapsed time in days
                      elapsed-ms (- review-time-ms last-review-ms)
                      elapsed-days (/ elapsed-ms 1000.0 60.0 60.0 24.0)
                      ;; Calculate retrievability at review time
                      r (retrievability elapsed-days stability)]
                  (new-stability difficulty stability r grade)))

        new-d (if first-review?
                (initial-difficulty grade)
                (new-difficulty difficulty grade))

        ;; Calculate optimal interval
        i-raw (interval desired-retention new-s)
        ;; Round and ensure minimum 1 day
        i-days (max 1.0 (Math/round i-raw))
        i-ms (* i-days 24.0 60.0 60.0 1000.0)

        ;; Calculate due date
        due-ms (+ review-time-ms i-ms)
        due-at #?(:clj (java.util.Date. due-ms)
                 :cljs (js/Date. due-ms))]

    {:stability new-s
     :difficulty new-d
     :interval i-days
     :interval-ms i-ms
     :due-at due-at}))
