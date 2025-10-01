#!/bin/bash
# Quick test script for the evaluator with real Gemini API

set -e

cd "$(dirname "$0")/.."

# Source .env for API keys
if [ -f .env ]; then
  export $(cat .env | grep -v '^#' | xargs)
fi

clj -M -e "
(require '[eval.core :as eval])
(require '[eval.llm :as llm])

(println \"=== Testing Evaluator with Gemini API ===\")
(println)

(def proposals
  {:simple \"Simple REST API with GET /users, GET /users/:id, POST /users\"
   :complex \"Complex API with REST, GraphQL, WebSocket, OAuth2, rate limiting, and monitoring\"
   :moderate \"REST API with standard CRUD, JWT auth, pagination, and OpenAPI docs\"})

(println \"Proposals:\")
(doseq [[id text] proposals]
  (println \"  \" id \"-\" (subs text 0 (min 60 (count text))) \"...\"))
(println)

(try
  (println \"Creating Gemini evaluator...\")
  (def evaluator (llm/make-evaluator :gemini {:model \"gemini-2.0-flash-exp\"}))

  (println \"Running evaluation (2 rounds)...\")
  (println)

  (def result (eval/evaluate proposals {:rounds 2} evaluator))

  (println \"=== Results ===\")
  (println)
  (println \"Final Ranking:\")
  (doseq [[rank id] (map-indexed vector (:ranking result))]
    (let [stats (get (:details result) id)]
      (println (format \"  %d. %s - median: %.1f, confidence: %s\"
                       (inc rank)
                       (name id)
                       (:median stats)
                       (name (:confidence stats))))))

  (println)
  (println \"Detailed Scores:\")
  (doseq [[id stats] (:details result)]
    (println (format \"  %s: rounds %s, median %.1f, stddev %.2f\"
                     (name id)
                     (vec (:scores stats))
                     (:median stats)
                     (:stddev stats))))

  (println)
  (println \"✓ Test completed successfully!\")

  (catch Exception e
    (println \"✗ Error:\" (.getMessage e))
    (when-let [data (ex-data e)]
      (println \"Details:\" data))
    (System/exit 1)))
"
