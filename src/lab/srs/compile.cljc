(ns lab.srs.compile
  "Intent compilation: SRS operations → kernel operations.
   Follows the pattern from core.struct (compile-intent)."
  (:require [lab.srs.schema :as schema]))

;; ============================================================================
;; Operation Builders
;; ============================================================================

(defn make-create-node
  "Build a create-node kernel operation."
  [id node-type props]
  {:op :create-node :id id :type node-type :props props})

(defn make-place
  "Build a place kernel operation."
  [id under at]
  {:op :place :id id :under under :at at})

(defn make-update-node
  "Build an update-node kernel operation."
  [id props]
  {:op :update-node :id id :props props})

;; ============================================================================
;; Platform Utilities
;; ============================================================================

(defn now-instant
  "Get current timestamp in platform-appropriate format."
  []
  #?(:clj (java.time.Instant/now)
     :cljs (js/Date.)))

(defn add-days
  "Add days to an instant/date."
  [instant days]
  #?(:clj (.plusSeconds instant (* days 86400))
     :cljs (js/Date. (+ (.getTime instant) (* days 86400 1000)))))

;; ============================================================================
;; Mock Scheduler (simple SM-2 intervals)
;; ============================================================================

(def default-intervals
  "Mock SRS intervals in days (simple version)."
  {:again 1
   :hard 3
   :good 7
   :easy 14})

(def grade->ease-factor
  "Map of grade to ease factor for SM-2 algorithm."
  {:easy 2.6
   :good 2.5
   :hard 2.3
   :again 2.0})

(defn calculate-next-review
  "Mock scheduler - calculate next due date and interval.
   In real implementation, this would use SM-2 or FSRS algorithm."
  [_current-props grade]
  (let [interval-days (get default-intervals grade 1)
        ease-factor (get grade->ease-factor grade 2.0)
        now (now-instant)
        due-date (add-days now interval-days)]
    {:srs/interval-days interval-days
     :srs/ease-factor ease-factor
     :srs/due-date due-date
     :srs/last-reviewed now}))

;; ============================================================================
;; ID Generation
;; ============================================================================

(defn gen-id
  "Generate unique ID for reviews/content nodes."
  [prefix]
  (str prefix "-" #?(:clj (str (java.util.UUID/randomUUID))
                     :cljs (str (random-uuid)))))

;; ============================================================================
;; Intent Compilation Multimethod
;; ============================================================================

(defmulti compile-srs-intent
  "Compiles a high-level SRS intent into a vector of kernel operations.
   Extensible via defmethod for plugin card types."
  (fn [intent _db] (:op intent)))

;; ----------------------------------------------------------------------------
;; :srs/create-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/create-card
  [{:keys [card-id deck-id card-type markdown-file front back tags props]} _db]
  (let [content-id (gen-id "content")
        card-props (merge (or props {})
                          {:card-type card-type
                           :markdown-file markdown-file
                           :tags (or tags [])
                           :srs/interval-days 1
                           :srs/ease-factor 2.5
                           :srs/due-date (now-instant)})
        content-props {:front front
                       :back back}]
    [(make-create-node card-id :card card-props)
     (make-create-node content-id :card-content content-props)
     (make-place card-id deck-id :last)
     (make-place content-id card-id :last)]))

;; ----------------------------------------------------------------------------
;; :srs/update-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/update-card
  [{:keys [card-id props]} _db]
  [(make-update-node card-id props)])

;; ----------------------------------------------------------------------------
;; :srs/review-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/review-card
  [{:keys [card-id grade timestamp latency-ms]} db]
  (let [review-id (gen-id "review")
        current-props (get-in db [:nodes card-id :props] {})
        next-review (calculate-next-review current-props grade)
        review-props {:review/card-id card-id
                      :review/grade grade
                      :review/timestamp timestamp
                      :review/latency-ms latency-ms}]
    [(make-create-node review-id :review review-props)
     (make-place review-id card-id :last)
     (make-update-node card-id next-review)]))

;; ----------------------------------------------------------------------------
;; :srs/schedule-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/schedule-card
  [{:keys [card-id due-date interval-days ease-factor]} _db]
  (let [props (cond-> {:srs/due-date due-date
                       :srs/interval-days interval-days}
                ease-factor (assoc :srs/ease-factor ease-factor))]
    [(make-update-node card-id props)]))

;; ============================================================================
;; Public API
;; ============================================================================

(defn compile-intent
  "Compile a single SRS intent to kernel ops.
   Validates schema first."
  [intent db]
  (when-not (schema/valid-srs-op? intent)
    (throw (ex-info "Invalid SRS operation"
                    {:intent intent
                     :explanation (schema/explain-srs-op intent)})))
  (compile-srs-intent intent db))

(defn compile-batch
  "Compile multiple SRS intents to kernel ops."
  [intents db]
  (mapcat #(compile-intent % db) intents))
