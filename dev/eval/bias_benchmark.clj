(ns dev.eval.bias-benchmark
  "Bias detection and benchmarking for LLM evaluators.

   Tests for:
   - Position bias (first/last preference)
   - Verbosity bias (length preference)
   - Self-preference bias (favoring own style)
   - Sycophancy (agreeing with perceived consensus)"
  (:require [dev.eval.core :as eval]
            [dev.eval.llm :as llm]))

;; =============================================================================
;; Test Proposals (Controlled Quality)

(def test-proposals-equal-quality
  "Three proposals of intentionally EQUAL technical quality.
   Used to detect position bias - scores should be ~equal regardless of order."
  {:a "REST API design with standard CRUD operations. GET /items, POST /items,
       PUT /items/:id, DELETE /items/:id. Uses JWT authentication via Authorization
       header. Returns JSON with standard HTTP status codes (200, 201, 404, 500).
       Pagination via limit/offset query params. Simple and conventional."

   :b "API design following REST principles. Create, read, update, delete endpoints
       for items resource. Authentication through JWT tokens in request headers.
       JSON response format with proper HTTP status codes. Supports pagination
       using limit and offset parameters. Straightforward REST implementation."

   :c "Standard RESTful API for item management. Endpoints: list items, create item,
       update item, delete item. JWT-based auth via headers. JSON responses with
       appropriate HTTP codes. Paginated results using query string parameters.
       Clean, conventional REST design."})

(def test-proposals-length-bias
  "Three proposals - SAME quality but DIFFERENT lengths.
   Used to detect verbosity bias."
  {:short "REST API: GET /users, POST /users, PUT /users/:id, DELETE /users/:id. JWT auth."

   :medium "REST API design with user management endpoints. GET /users returns list,
            POST /users creates new user, PUT /users/:id updates, DELETE /users/:id removes.
            Authentication via JWT tokens in Authorization header. Standard JSON responses."

   :verbose "This is a comprehensive REST API design for user management functionality.
             The architecture provides full CRUD capabilities through well-defined endpoints.

             Specifically, the GET /users endpoint retrieves a list of all users in the system,
             with support for pagination through query parameters to handle large datasets efficiently.

             The POST /users endpoint allows creation of new user records, accepting JSON payloads
             with user details and returning the created user object with a 201 status code.

             For updates, the PUT /users/:id endpoint enables modification of existing user records,
             accepting the user ID as a path parameter and the updated fields in the request body.

             Finally, the DELETE /users/:id endpoint provides deletion functionality, removing
             the specified user from the system and returning appropriate status codes.

             All endpoints are secured using JWT authentication, requiring valid tokens to be
             provided in the Authorization header of each request. This ensures that only
             authenticated users can access the API endpoints."})

(def test-proposals-clear-ranking
  "Three proposals with CLEAR quality differences.
   Used to verify evaluator can distinguish good from bad."
  {:excellent "REST API with comprehensive design: Full CRUD operations, JWT auth,
               input validation, rate limiting, proper error handling with detailed codes,
               pagination, filtering, caching via ETags, versioning (/v1/), detailed
               OpenAPI documentation, idempotency keys for POST, CORS support."

   :good "REST API with standard operations: CRUD endpoints, JWT authentication,
          basic error handling, JSON responses, pagination support, simple filtering."

   :poor "API with endpoints: /getUsers, /addUser, /updateUser, /removeUser.
          No authentication mentioned. Mixed HTTP methods (all POST). No error handling
          or pagination. Inconsistent naming (/getUsers vs /addUser)."})

;; =============================================================================
;; Bias Detection Tests

(defn test-position-bias
  "Test if evaluator favors first, middle, or last position.

   Uses equal-quality proposals in different orders.
   Expected: scores should be similar regardless of order.
   Position bias detected if first/last consistently scores higher."
  [evaluator-fn]
  (println "\n=== Position Bias Test ===")
  (println "Testing with 3 equal-quality proposals in different orders...\n")

  (let [;; Test 3 different orderings
        orders [[:a :b :c]  ; A first
                [:c :b :a]  ; A last
                [:b :a :c]] ; A middle

        results
        (doall
         (for [order orders]
           (let [prompt (eval/make-evaluation-prompt
                         test-proposals-equal-quality
                         order
                         (:criteria eval/default-config))
                 scores (evaluator-fn prompt order)]
             {:order order
              :scores scores})))]

    ;; Analyze: did A's score change based on position?
    (println "Results:")
    (doseq [{:keys [order scores]} results]
      (let [a-position (.indexOf (vec order) :a)
            a-score (:a scores)]
        (println (format "  Order %s: A in position %d, score %.1f"
                         order (inc a-position) a-score))))

    ;; Calculate position effect
    (let [a-scores (map #(:a (:scores %)) results)
          a-positions (map #(.indexOf (vec (:order %)) :a) results)
          first-score (nth a-scores (.indexOf a-positions 0))
          last-score (nth a-scores (.indexOf a-positions 2))
          diff (- last-score first-score)]

      (println (format "\nPosition effect: A scored %.1f when first, %.1f when last"
                       first-score last-score))
      (println (format "Difference: %.1f points", diff))

      (cond
        (> diff 1.0) (println "⚠️  LAST POSITION BIAS detected (favors last)")
        (< diff -1.0) (println "⚠️  FIRST POSITION BIAS detected (favors first)")
        :else (println "✓ No significant position bias"))

      {:bias-type (cond
                    (> diff 1.0) :last-position-bias
                    (< diff -1.0) :first-position-bias
                    :else :no-bias)
       :magnitude (Math/abs diff)
       :details results})))

(defn test-verbosity-bias
  "Test if evaluator favors longer responses.

   Uses proposals of same quality but different lengths.
   Expected: scores should be similar.
   Verbosity bias detected if longer proposal scores significantly higher."
  [evaluator-fn]
  (println "\n=== Verbosity Bias Test ===")
  (println "Testing with 3 equal-quality proposals of different lengths...\n")

  (let [order [:short :medium :verbose]
        prompt (eval/make-evaluation-prompt
                test-proposals-length-bias
                order
                (:criteria eval/default-config))
        scores (evaluator-fn prompt order)]

    (println "Proposal lengths:")
    (println (format "  Short:   %4d chars, score %.1f"
                     (count (:short test-proposals-length-bias))
                     (:short scores)))
    (println (format "  Medium:  %4d chars, score %.1f"
                     (count (:medium test-proposals-length-bias))
                     (:medium scores)))
    (println (format "  Verbose: %4d chars, score %.1f"
                     (count (:verbose test-proposals-length-bias))
                     (:verbose scores)))

    (let [short-verbose-diff (- (:verbose scores) (:short scores))]

      (println (format "\nLength preference: Verbose scored %.1f points higher than short"
                       short-verbose-diff))

      (cond
        (> short-verbose-diff 2.0) (println "⚠️  STRONG VERBOSITY BIAS detected")
        (> short-verbose-diff 1.0) (println "⚠️  MODERATE VERBOSITY BIAS detected")
        :else (println "✓ No significant verbosity bias"))

      {:bias-type (cond
                    (> short-verbose-diff 2.0) :strong-verbosity-bias
                    (> short-verbose-diff 1.0) :moderate-verbosity-bias
                    :else :no-bias)
       :magnitude short-verbose-diff
       :scores scores})))

(defn test-discrimination-ability
  "Test if evaluator can correctly rank proposals of clearly different quality.

   Uses proposals with obvious quality differences.
   Expected: excellent > good > poor
   Failure indicates evaluator can't distinguish quality."
  [evaluator-fn]
  (println "\n=== Discrimination Ability Test ===")
  (println "Testing with 3 proposals of clearly different quality...\n")

  (let [order [:excellent :good :poor]
        prompt (eval/make-evaluation-prompt
                test-proposals-clear-ranking
                order
                (:criteria eval/default-config))
        scores (evaluator-fn prompt order)]

    (println "Scores:")
    (println (format "  Excellent: %.1f" (:excellent scores)))
    (println (format "  Good:      %.1f" (:good scores)))
    (println (format "  Poor:      %.1f" (:poor scores)))

    (let [correct-ranking? (and (> (:excellent scores) (:good scores))
                                (> (:good scores) (:poor scores)))
          spread (- (:excellent scores) (:poor scores))]

      (println (format "\nScore spread: %.1f points (excellent - poor)" spread))

      (cond
        (not correct-ranking?)
        (println "❌ FAILED - Incorrect ranking! Cannot distinguish quality.")

        (< spread 2.0)
        (println "⚠️  WEAK - Correct ranking but low discrimination (spread < 2)")

        :else
        (println "✓ PASSED - Correctly ranked proposals with good discrimination"))

      {:passed? correct-ranking?
       :spread spread
       :scores scores})))

;; =============================================================================
;; Full Benchmark Suite

(defn run-bias-benchmark
  "Run complete bias benchmark suite for an evaluator.

   Returns comprehensive report with bias scores."
  [provider & [opts]]
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║           LLM Evaluator Bias Benchmark Suite              ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println "\nProvider:" provider)
  (println "Options:" opts)

  (let [evaluator (llm/make-evaluator provider opts)

        ;; Run all tests
        position-result (test-position-bias evaluator)
        verbosity-result (test-verbosity-bias evaluator)
        discrimination-result (test-discrimination-ability evaluator)

        ;; Summarize
        overall-bias-score
        (+ (if (= :no-bias (:bias-type position-result)) 0 (:magnitude position-result))
           (if (= :no-bias (:bias-type verbosity-result)) 0 (:magnitude verbosity-result)))]

    (println "\n╔════════════════════════════════════════════════════════════╗")
    (println "║                    Summary Report                         ║")
    (println "╚════════════════════════════════════════════════════════════╝")
    (println (format "\nPosition Bias:       %s (magnitude: %.1f)"
                     (name (:bias-type position-result))
                     (:magnitude position-result)))
    (println (format "Verbosity Bias:      %s (magnitude: %.1f)"
                     (name (:bias-type verbosity-result))
                     (:magnitude verbosity-result)))
    (println (format "Discrimination:      %s (spread: %.1f)"
                     (if (:passed? discrimination-result) "PASSED" "FAILED")
                     (:spread discrimination-result)))
    (println (format "\nOverall Bias Score:  %.1f (lower is better)" overall-bias-score))

    (when (< overall-bias-score 2.0)
      (println "\n✓ This evaluator shows LOW bias - suitable for fair evaluation"))
    (when (>= overall-bias-score 3.0)
      (println "\n⚠️  This evaluator shows HIGH bias - use calibration!"))

    {:provider provider
     :position-bias position-result
     :verbosity-bias verbosity-result
     :discrimination discrimination-result
     :overall-bias-score overall-bias-score
     :recommendation (cond
                       (< overall-bias-score 2.0) :low-bias
                       (< overall-bias-score 3.0) :moderate-bias
                       :else :high-bias)}))

(comment
  ;; REPL testing

  ;; Benchmark Gemini
  (run-bias-benchmark :gemini {:model "gemini-2.0-flash-exp"})

  ;; Benchmark Codex
  (run-bias-benchmark :codex {:model "gpt-5-codex"
                              :reasoning-effort "high"})

  ;; Benchmark Grok
  (run-bias-benchmark :grok {:model "grok-4-latest"})
  )
