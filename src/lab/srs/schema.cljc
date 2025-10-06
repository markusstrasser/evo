(ns lab.srs.schema
  "Malli schemas for SRS operations (based on Codex/Grok proposals).
  
  This module defines the contract for SRS operations using Malli schemas.
  The schemas are designed for:
  - Clear validation with actionable error messages
  - Schema composition and reuse via registry
  - Generator support for property-based testing
  - Type safety with closed maps
  
  See also:
  - lab.srs.compile - Compiles SRS operations to kernel operations
  - lab.srs.demo - Usage examples and integration tests"
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]))

;; ============================================================================
;; Type Schemas
;; ============================================================================

(def CardId
  "Card identifier (string).
  
  Note: Using string IDs for simplicity. Could be enhanced with UUID validation
  or custom ID format (e.g., 'card-{uuid}') in future."
  [:string {:min 1
            :doc "Non-empty string identifier for a card"
            :gen/gen #(str "card-" (random-uuid))}])

(def DeckId
  "Deck identifier (string)."
  [:string {:min 1
            :doc "Non-empty string identifier for a deck"
            :gen/gen #(str "deck-" (random-uuid))}])

(def ReviewId
  "Review identifier (string)."
  [:string {:min 1
            :doc "Non-empty string identifier for a review event"
            :gen/gen #(str "review-" (random-uuid))}])

(def MediaId
  "Media identifier (string)."
  [:string {:min 1
            :doc "Non-empty string identifier for media (images, audio, etc.)"
            :gen/gen #(str "media-" (random-uuid))}])

(def CardType
  "Extensible card type - plugins can add new types.
  
  Built-in types:
  - :basic - Traditional front/back flashcard
  - :cloze - Fill-in-the-blank style card
  - :image-occlusion - Image with hidden regions"
  [:enum {:doc "Card type determines rendering and review behavior"}
   :basic :cloze :image-occlusion])

(def ReviewGrade
  "SM-2 algorithm review grades.
  
  Grade meanings:
  - :again - Complete failure, restart learning
  - :hard - Difficult but successful recall
  - :good - Normal successful recall
  - :easy - Trivially easy recall"
  [:enum {:doc "Review performance grade (SM-2 algorithm)"}
   :again :hard :good :easy])

;; ============================================================================
;; SRS Operation Schemas (4 core operations)
;; ============================================================================

(def CreateCard
  "Create a new card with content. Compiles to:
   - create-node (card)
   - create-node (card-content child)
   - place (card under deck)
   - place (content under card)
  
  Example:
    {:op :srs/create-card
     :card-id \"card-123\"
     :deck-id \"deck-spanish\"
     :card-type :basic
     :markdown-file \"spanish-vocab.md\"
     :front \"perro\"
     :back \"dog\"
     :tags [\"animals\" \"nouns\"]
     :props {:difficulty 0.3}}"
  [:map {:closed true
         :doc "Create a new SRS card"}
   [:op [:= :srs/create-card]]
   [:card-id CardId]
   [:deck-id DeckId]
   [:card-type CardType]
   [:markdown-file [:string {:min 1 :doc "Source markdown file path"}]]
   [:front [:string {:min 1 :doc "Front side of card (question/prompt)"}]]
   [:back [:string {:min 1 :doc "Back side of card (answer)"}]]
   [:tags {:optional true} [:vector :string]]
   [:props {:optional true} [:map-of :keyword :any]]])

(def UpdateCard
  "Update card content or metadata. Compiles to:
   - update-node (on card or card-content)
  
  Example:
    {:op :srs/update-card
     :card-id \"card-123\"
     :props {:front \"¿Cómo se dice 'dog' en español?\"
             :tags [\"animals\" \"questions\"]}}"
  [:map {:closed true
         :doc "Update card properties"}
   [:op [:= :srs/update-card]]
   [:card-id CardId]
   [:props [:map-of :keyword :any]]])

(def ReviewCard
  "Record a review event with grade. Compiles to:
   - create-node (review)
   - place (review under card)
   - update-node (card scheduling metadata)

  Example:
    {:op :srs/review-card
     :card-id \"card-123\"
     :grade :good
     :timestamp \"2025-10-06T15:25:45.209560Z\"
     :latency-ms 3500}"
  [:map {:closed true
         :doc "Record a review event"}
   [:op [:= :srs/review-card]]
   [:card-id CardId]
   [:grade ReviewGrade]
   [:timestamp [:string {:min 1 :doc "ISO 8601 timestamp string"}]]
   [:latency-ms {:optional true} pos-int?]])

(def ScheduleCard
  "Update card scheduling metadata. Compiles to:
   - update-node (card scheduling props)

  Example:
    {:op :srs/schedule-card
     :card-id \"card-123\"
     :due-date \"2025-10-13T15:25:45.209592Z\"
     :interval-days 7
     :ease-factor 2.5}"
  [:map {:closed true
         :doc "Update card scheduling parameters"}
   [:op [:= :srs/schedule-card]]
   [:card-id CardId]
   [:due-date [:string {:min 1 :doc "ISO 8601 timestamp string"}]]
   [:interval-days pos-int?]
   [:ease-factor {:optional true} float?]])

;; ============================================================================
;; Combined Schema (Discriminated Union)
;; ============================================================================

(def SrsOperation
  "Discriminated union of all SRS operations.
  
  Uses :multi for clean error messages - the :op field determines which
  schema variant to validate against. This gives much better error messages
  than :or unions, which report errors from all branches.
  
  Examples:
    (m/validate SrsOperation {:op :srs/create-card ...})
    (m/explain SrsOperation {:op :srs/invalid ...})
    ;=> {:op [\"invalid dispatch value\"]}"
  [:multi {:dispatch :op
           :doc "SRS operation discriminated by :op key"}
   [:srs/create-card CreateCard]
   [:srs/update-card UpdateCard]
   [:srs/review-card ReviewCard]
   [:srs/schedule-card ScheduleCard]])

;; ============================================================================
;; Schema Registry
;; ============================================================================

(def registry
  "Composite registry with all SRS schemas.
  
  Enables schema composition and reference by keyword:
    [:ref :srs/operation]
    [:ref :srs/card-id]
  
  Usage:
    (m/validate :srs/operation op {:registry registry})"
  (mr/composite-registry
   (m/default-schemas)
   {:srs/card-id CardId
    :srs/deck-id DeckId
    :srs/review-id ReviewId
    :srs/media-id MediaId
    :srs/card-type CardType
    :srs/review-grade ReviewGrade
    :srs/create-card CreateCard
    :srs/update-card UpdateCard
    :srs/review-card ReviewCard
    :srs/schedule-card ScheduleCard
    :srs/operation SrsOperation}))

;; Pre-compile validators for performance
(def ^:private compiled-schemas
  {:operation (m/validator SrsOperation {:registry registry})
   :create-card (m/validator CreateCard {:registry registry})
   :update-card (m/validator UpdateCard {:registry registry})
   :review-card (m/validator ReviewCard {:registry registry})
   :schedule-card (m/validator ScheduleCard {:registry registry})})

;; ============================================================================
;; Validation API
;; ============================================================================

(defn valid-srs-op?
  "Returns true if op is a valid SRS operation.
  
  Uses pre-compiled validator for performance.
  
  Examples:
    (valid-srs-op? {:op :srs/create-card
                    :card-id \"c1\"
                    :deck-id \"d1\"
                    :card-type :basic
                    :markdown-file \"cards.md\"
                    :front \"Question\"
                    :back \"Answer\"})
    ;=> true
    
    (valid-srs-op? {:op :srs/invalid})
    ;=> false"
  [op]
  ((:operation compiled-schemas) op))

(defn explain-srs-op
  "Returns detailed explanation map for invalid SRS operation.
  
  Returns nil if the operation is valid.
  Use humanize-srs-op for user-friendly error messages.
  
  Examples:
    (explain-srs-op {:op :srs/create-card :card-type :invalid})
    ;=> {:schema [...] :value {...} :errors [...]}"
  [op]
  (m/explain SrsOperation op {:registry registry}))

(defn humanize-srs-op
  "Returns human-readable error messages for invalid operation.
  
  Returns nil if the operation is valid.
  Error messages are suitable for displaying to users.
  
  Examples:
    (humanize-srs-op {:op :srs/create-card :card-type :invalid})
    ;=> {:card-type [\"should be either :basic, :cloze or :image-occlusion\"]}
    
    (humanize-srs-op {:op :srs/create-card :front \"\" ...})
    ;=> {:front [\"should be at least 1 character\"]}"
  [op]
  (when-let [explanation (explain-srs-op op)]
    (me/humanize explanation)))

(defn operation-type
  "Returns the operation type keyword (:op field).
  
  Returns nil if op is not a map or has no :op field.
  Does not validate the operation.
  
  Examples:
    (operation-type {:op :srs/create-card ...})
    ;=> :srs/create-card
    
    (operation-type {:no-op \"here\"})
    ;=> nil"
  [op]
  (when (map? op)
    (:op op)))

(defn operation-type?
  "Returns true if op has the specified operation type.
  
  Does not validate the operation - only checks the :op field.
  
  Examples:
    (operation-type? {:op :srs/create-card ...} :srs/create-card)
    ;=> true
    
    (operation-type? {:op :srs/create-card ...} :srs/update-card)
    ;=> false"
  [op expected-type]
  (= expected-type (operation-type op)))
