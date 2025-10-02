(ns eval-repl
  "REPL utilities for testing the evaluation system."
  (:require [dev.eval.core :as eval]
            [dev.eval.llm :as llm]
            [dev.eval.async :as eval-async]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Mock Data & Evaluators

(def sample-proposals
  "Sample API design proposals for testing."
  {:simple
   "Simple REST API with three endpoints:
    - GET /users - list users
    - GET /users/:id - get user
    - POST /users - create user

    Uses standard HTTP methods and status codes.
    Authentication via JWT tokens."

   :complex
   "Comprehensive API design with advanced features:
    - RESTful resource endpoints with full CRUD
    - GraphQL interface for flexible queries
    - WebSocket support for real-time updates
    - Rate limiting and throttling
    - OAuth2 authentication with refresh tokens
    - Versioning via Accept headers
    - HATEOAS links for discoverability
    - Pagination, filtering, sorting on collections
    - Caching with ETags and conditional requests
    - Request validation with detailed error messages
    - Audit logging and monitoring hooks
    - Circuit breaker pattern for downstream services
    - Comprehensive OpenAPI documentation
    - SDK generation for multiple languages"

   :moderate
   "Well-structured REST API with practical features:
    - Resource-oriented endpoints (/users, /posts, /comments)
    - Standard HTTP verbs and status codes
    - JSON request/response format
    - Token-based authentication (JWT)
    - Basic error handling with error codes
    - Pagination via query params (limit/offset)
    - Simple filtering and sorting
    - API versioning in URL path (/v1/)
    - OpenAPI/Swagger documentation
    - Rate limiting per API key"

   :verbose
   (apply str (repeat 20 "This is a verbose proposal with lots of repetitive text. "))

   :minimal
   "GET /data, POST /data."})

(defn mock-evaluator
  "Mock evaluator that returns semi-random but consistent scores.
   Useful for testing without API calls."
  [prompt order]
  (into {}
        (map (fn [id]
               ;; Generate pseudo-random but deterministic score based on ID
               (let [hash-val (hash id)
                     score (+ 5 (mod (Math/abs hash-val) 6))]  ; 5-10
                 [id (double score)]))
             order)))

(defn biased-mock-evaluator
  "Mock evaluator that exhibits position bias (prefers first position).
   Useful for testing calibration."
  [prompt order]
  (into {}
        (map-indexed
         (fn [idx id]
           ;; First position gets +2 bonus, last gets -1 penalty
           (let [base-score (+ 5 (rand-int 4))  ; 5-8
                 position-bonus (cond
                                  (zero? idx) 2.0        ; first
                                  (= idx (dec (count order))) -1.0  ; last
                                  :else 0.0)]
             [id (double (+ base-score position-bonus))]))
         order)))

(defn slow-mock-evaluator
  "Mock evaluator with artificial delay to test async behavior."
  [delay-ms]
  (fn [prompt order]
    (Thread/sleep delay-ms)
    (mock-evaluator prompt order)))

;; =============================================================================
;; Testing Helpers

(defn quick-eval
  "Quick evaluation test with mock evaluator."
  ([]
   (quick-eval sample-proposals))
  ([proposals]
   (eval/evaluate proposals {} mock-evaluator)))

(defn test-normalization
  "Test length normalization on verbose proposals."
  []
  (let [max-len 100
        normalized (eval/normalize-proposals sample-proposals max-len)]
    (pp/pprint
     {:original-lengths (into {} (map (fn [[k v]] [k (count v)]) sample-proposals))
      :normalized-lengths (into {} (map (fn [[k v]] [k (count v)]) normalized))
      :verbose-normalized (get normalized :verbose)})))

(defn test-position-bias
  "Test position bias calibration with biased evaluator."
  []
  (println "\n=== Without Calibration ===")
  (let [result-uncalibrated
        (eval/evaluate sample-proposals
                       {:position-weights [1.0 1.0 1.0 1.0 1.0]}  ; no calibration
                       biased-mock-evaluator)]
    (pp/pprint (:ranking result-uncalibrated)))

  (println "\n=== With Calibration ===")
  (let [result-calibrated
        (eval/evaluate sample-proposals
                       {}  ; uses default calibration weights
                       biased-mock-evaluator)]
    (pp/pprint (:ranking result-calibrated))))

(defn test-parallel
  "Test parallel evaluation (should be faster than sequential)."
  []
  (let [delay-ms 500
        slow-eval (slow-mock-evaluator delay-ms)]

    (println "\n=== Sequential Evaluation ===")
    (let [start (System/currentTimeMillis)
          _ (eval/evaluate sample-proposals {:rounds 3} slow-eval)
          elapsed (- (System/currentTimeMillis) start)]
      (println "Time:" elapsed "ms (expected ~1500ms)"))

    (println "\n=== Parallel Evaluation ===")
    (let [start (System/currentTimeMillis)
          _ (eval-async/evaluate-parallel sample-proposals {:rounds 3} slow-eval)
          elapsed (- (System/currentTimeMillis) start)]
      (println "Time:" elapsed "ms (expected ~500ms)"))))

(defn show-prompt
  "Show what the evaluation prompt looks like."
  ([]
   (show-prompt sample-proposals))
  ([proposals]
   (let [order (vec (keys proposals))
         criteria (:criteria eval/default-config)
         prompt (eval/make-evaluation-prompt proposals order criteria)]
     (println prompt))))

(defn test-score-aggregation
  "Test score aggregation and statistics."
  []
  (let [;; Simulate 3 rounds of scores
        rounds-data [{:a 8.0 :b 7.0 :c 6.5}
                     {:a 8.5 :b 7.2 :c 6.8}
                     {:a 7.8 :b 7.1 :c 6.3}]
        aggregated (eval/aggregate-scores rounds-data)
        ranking (eval/rank-proposals aggregated)]

    (println "\n=== Aggregated Scores ===")
    (pp/pprint aggregated)

    (println "\n=== Final Ranking ===")
    (pp/pprint ranking)))

;; =============================================================================
;; Real API Testing (requires API keys)

(defn test-gemini
  "Test with real Gemini API (requires GEMINI_API_KEY)."
  ([]
   (test-gemini {:simple "Simple API design"
                 :moderate "Moderate API design"
                 :complex "Complex API design"}))
  ([proposals]
   (let [evaluator (llm/make-evaluator :gemini {:model "gemini-2.0-flash-exp"})]
     (println "\n=== Testing Gemini API ===")
     (try
       (let [result (eval/evaluate proposals {:rounds 2} evaluator)]
         (pp/pprint (:ranking result))
         (pp/pprint (:details result))
         result)
       (catch Exception e
         (println "Error:" (.getMessage e))
         (println "Cause:" (ex-data e)))))))

(defn test-all-providers
  "Test all LLM providers (requires all API keys)."
  [proposals]
  (doseq [provider [:gemini :codex :grok]]
    (println "\n========================================")
    (println "Testing provider:" provider)
    (println "========================================")
    (try
      (let [evaluator (llm/make-evaluator provider)
            result (eval/evaluate proposals {:rounds 2} evaluator)]
        (println "\nRanking:" (:ranking result))
        (pp/pprint (:details result)))
      (catch Exception e
        (println "Error:" (.getMessage e))))))

;; =============================================================================
;; Convenience Functions

(defn run-all-tests
  "Run all local tests (no API calls)."
  []
  (println "====================================")
  (println "Testing Normalization")
  (println "====================================")
  (test-normalization)

  (println "\n====================================")
  (println "Testing Score Aggregation")
  (println "====================================")
  (test-score-aggregation)

  (println "\n====================================")
  (println "Testing Quick Eval")
  (println "====================================")
  (pp/pprint (quick-eval))

  (println "\n====================================")
  (println "Testing Position Bias Calibration")
  (println "====================================")
  (test-position-bias)

  (println "\n====================================")
  (println "Testing Parallel Evaluation")
  (println "====================================")
  (test-parallel)

  (println "\n====================================")
  (println "All tests complete!")
  (println "===================================="))

(comment
  ;; REPL workflow

  ;; 1. Quick smoke test
  (quick-eval)

  ;; 2. Show what prompt looks like
  (show-prompt)

  ;; 3. Test individual components
  (test-normalization)
  (test-score-aggregation)
  (test-position-bias)
  (test-parallel)

  ;; 4. Run all local tests
  (run-all-tests)

  ;; 5. Test with real API (requires API key)
  (test-gemini)
  (test-all-providers {:a "Design A" :b "Design B" :c "Design C"})

  ;; 6. Custom evaluation
  (eval/evaluate
   {:design-1 "My first API design..."
    :design-2 "My second API design..."}
   {:rounds 3
    :max-length 1000}
   (llm/make-evaluator :gemini))
  )
