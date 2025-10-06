(ns lab.srs.compile
  "Intent compilation: SRS operations → kernel operations.
   Follows the pattern from core.struct (compile-intent)."
  (:require [clojure.string :as str]
            [lab.srs.schema :as schema]))

;; ============================================================================
;; Operation Builders
;; ============================================================================

(defn make-create-node
  "Build a create-node kernel operation.
   Returns a map with :op, :id, :type, and :props keys."
  [id node-type props]
  {:pre [(string? id) (keyword? node-type) (map? props)]
   :post [(map? %) (= :create-node (:op %))]}
  {:op :create-node
   :id id
   :type node-type
   :props props})

(defn make-place
  "Build a place kernel operation.
   Places node `id` under `parent-id` at position `at`."
  [id parent-id at]
  {:pre [(string? id) (string? parent-id) (keyword? at)]
   :post [(map? %) (= :place (:op %))]}
  {:op :place
   :id id
   :under parent-id
   :at at})

(defn make-update-node
  "Build an update-node kernel operation.
   Updates node `id` with the given `props` map."
  [id props]
  {:pre [(string? id) (map? props)]
   :post [(map? %) (= :update-node (:op %))]}
  {:op :update-node
   :id id
   :props props})

;; ============================================================================
;; Platform Utilities
;; ============================================================================

(defn now-instant
  "Returns current timestamp in platform-appropriate format.
   Clj: java.time.Instant, Cljs: js/Date"
  []
  #?(:clj (java.time.Instant/now)
     :cljs (js/Date.)))

(defn add-days
  "Adds `days` to an instant/date, returning a new instant.
   Handles platform differences between JVM and JS."
  [instant days]
  {:pre [(some? instant) (number? days)]}
  #?(:clj (.plusSeconds instant (* days 86400))
     :cljs (js/Date. (+ (.getTime instant) (* days 86400 1000)))))

;; ============================================================================
;; Mock Scheduler (simple SM-2 intervals)
;; ============================================================================

(def default-intervals
  "Default SRS intervals in days for each grade.
   Simple mock version - real implementation would use SM-2 or FSRS."
  {:again 1
   :hard 3
   :good 7
   :easy 14})

(def grade->ease-factor
  "Maps review grade to ease factor for SM-2 algorithm.
   Higher grades increase ease factor (longer future intervals)."
  {:easy 2.6
   :good 2.5
   :hard 2.3
   :again 2.0})

(defn calculate-next-review
  "Calculates next review schedule based on current card state and grade.

   Returns map with:
   - :srs/interval-days - days until next review
   - :srs/ease-factor - difficulty multiplier
   - :srs/due-date - absolute timestamp for next review
   - :srs/last-reviewed - timestamp of this review

   Note: This is a simplified mock. Real implementation should use
   SM-2 or FSRS algorithm with card history."
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
  "Generates a unique ID with the given prefix.
   Format: '{prefix}-{uuid}'

   Examples:
   - (gen-id \"review\") => \"review-f47ac10b-58cc-4372-a567-0e02b2c3d479\"
   - (gen-id \"content\") => \"content-8b4c9d5e-3f2a-4c6b-9e1a-7d8f0b2c4a6e\""
  [prefix]
  {:pre [(string? prefix)]
   :post [(string? %) (str/starts-with? % (str prefix "-"))]}
  (str prefix "-" #?(:clj (str (java.util.UUID/randomUUID))
                     :cljs (str (random-uuid)))))

;; ============================================================================
;; Intent Compilation Multimethod
;; ============================================================================

(defmulti compile-srs-intent
  "Compiles a high-level SRS intent into a vector of kernel operations.

   Dispatches on the :op key of the intent map.
   Extensible via defmethod for plugin card types.

   Args:
   - intent: SRS operation map with :op key
   - db: Current database state (for lookups)

   Returns: Vector of kernel operations (create-node, place, update-node)"
  (fn [intent _db] (:op intent)))

;; ----------------------------------------------------------------------------
;; :srs/create-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/create-card
  [{:keys [card-id deck-id card-type markdown-file front back tags props]} _db]
  (let [content-id (gen-id "content")

        ;; Card props: merge user props with SRS metadata
        card-props (-> (or props {})
                       (merge {:card-type card-type
                               :markdown-file markdown-file
                               :tags (or tags [])
                               :srs/interval-days 1
                               :srs/ease-factor 2.5
                               :srs/due-date (now-instant)}))

        ;; Content props: front/back text (could be extended for cloze, etc.)
        content-props {:front front
                       :back back}]

    ;; Create card node, content node, and establish hierarchy
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

        ;; Look up current card state for scheduling calculation
        current-props (get-in db [:nodes card-id :props] {})

        ;; Calculate new schedule based on performance grade
        next-review (calculate-next-review current-props grade)

        ;; Review record captures this review session
        review-props {:review/card-id card-id
                      :review/grade grade
                      :review/timestamp timestamp
                      :review/latency-ms latency-ms}]

    ;; Create review record, link to card, update card schedule
    [(make-create-node review-id :review review-props)
     (make-place review-id card-id :last)
     (make-update-node card-id next-review)]))

;; ----------------------------------------------------------------------------
;; :srs/schedule-card
;; ----------------------------------------------------------------------------

(defmethod compile-srs-intent :srs/schedule-card
  [{:keys [card-id due-date interval-days ease-factor]} _db]
  (let [;; Build schedule props, only including provided values
        schedule-props (cond-> {:srs/due-date due-date
                                :srs/interval-days interval-days}
                         ease-factor (assoc :srs/ease-factor ease-factor))]
    [(make-update-node card-id schedule-props)]))

;; ============================================================================
;; Public API
;; ============================================================================

(defn compile-intent
  "Compiles a single SRS intent to kernel operations.

   Validates the intent schema before compilation.
   Throws ex-info if intent is invalid.

   Args:
   - intent: SRS operation map (must pass schema validation)
   - db: Current database state

   Returns: Vector of kernel operations

   Throws: ex-info with :intent and :explanation on validation failure"
  [intent db]
  (when-not (schema/valid-srs-op? intent)
    (throw (ex-info "Invalid SRS operation"
                    {:intent intent
                     :explanation (schema/explain-srs-op intent)})))
  (compile-srs-intent intent db))

(defn compile-batch
  "Compiles multiple SRS intents to kernel operations.

   Each intent is validated and compiled independently.
   Results are concatenated into a single flat vector.

   Args:
   - intents: Sequence of SRS operation maps
   - db: Current database state

   Returns: Flat vector of all kernel operations"
  [intents db]
  (mapcat #(compile-intent % db) intents))
