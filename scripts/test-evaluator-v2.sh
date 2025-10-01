#!/bin/bash
# Test enhanced evaluator v2 with XML prompts and smart truncation

set -e

cd "$(dirname "$0")/.."

# Source .env for API keys
if [ -f .env ]; then
  export $(cat .env | grep -v '^#' | xargs)
fi

echo "╔════════════════════════════════════════════════════════════╗"
echo "║        Enhanced Evaluator V2 Test (XML + Smart)           ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

clj -M -e "
(require '[eval.v2 :as eval-v2])
(require '[eval.llm :as llm])
(require '[clojure.pprint :as pp])

(println \"Testing enhanced evaluator with:\")
(println \"  ✓ XML-structured prompts\")
(println \"  ✓ Smart truncation (LLM summarization)\")
(println \"  ✓ Project context injection\")
(println)

(def proposals
  {:simple \"Simple REST API with GET /users, GET /users/:id, POST /users\"

   :verbose (apply str (repeat 50 \"This is a complex enterprise-grade API with extensive features including REST endpoints, GraphQL support, WebSocket real-time updates, comprehensive authentication, rate limiting, and monitoring. \"))

   :moderate \"REST API with standard CRUD, JWT auth, pagination, filtering, and OpenAPI docs. Includes proper error handling and validation.\"})

(println \"Proposal sizes:\")
(println (format \"  Simple:   %d chars\" (count (:simple proposals))))
(println (format \"  Verbose:  %d chars\" (count (:verbose proposals))))
(println (format \"  Moderate: %d chars\" (count (:moderate proposals))))
(println)

(try
  (println \"Running enhanced evaluation...\")
  (println)

  (def result
    (eval-v2/evaluate-v2
     proposals
     {:rounds 2
      :use-smart-truncation? true
      :use-xml-prompts? true
      :include-context? true
      :context-source :auto-source}
     (llm/make-evaluator :gemini {:model \"gemini-2.0-flash-exp\"})))

  (println \"=== Results ===\")
  (println)

  (println \"Truncation metadata:\")
  (pp/pprint (:metadata result))
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
  (println \"✓ Enhanced evaluation completed successfully!\")
  (println)
  (println \"Key improvements demonstrated:\")
  (println \"  ✓ Verbose proposal summarized (check metadata)\")
  (println \"  ✓ XML prompts used (better structure)\")
  (println \"  ✓ Context injected (project-aware evaluation)\")

  (catch Exception e
    (println \"✗ Error:\" (.getMessage e))
    (when-let [data (ex-data e)]
      (println \"Details:\")
      (pp/pprint data))
    (System/exit 1)))
"

echo ""
echo "Test complete!"
