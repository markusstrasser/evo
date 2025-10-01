#!/bin/bash
# Benchmark all LLM evaluators for biases

set -e

cd "$(dirname "$0")/.."

# Source .env for API keys
if [ -f .env ]; then
  export $(cat .env | grep -v '^#' | xargs)
fi

echo "╔════════════════════════════════════════════════════════════╗"
echo "║     Multi-Model LLM Evaluator Bias Benchmark Suite        ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "This will test position bias, verbosity bias, and"
echo "discrimination ability across Gemini, Codex, and Grok."
echo ""

TIMESTAMP=$(date +%Y-%m-%d-%H-%M)
OUTPUT_DIR="research/bias-benchmarks/$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

clj -M -e "
(require '[eval.bias-benchmark :as bench])
(require '[clojure.pprint :as pp])

(println \"Testing evaluators...\")
(println)

;; Test all three providers
(def gemini-result
  (bench/run-bias-benchmark :gemini {:model \"gemini-2.0-flash-exp\"}))

(println \"\n\n\")

(def codex-result
  (bench/run-bias-benchmark :codex {:model \"gpt-5-codex\"
                                     :reasoning-effort \"high\"}))

(println \"\n\n\")

(def grok-result
  (bench/run-bias-benchmark :grok {:model \"grok-4-latest\"}))

;; Save results
(spit \"$OUTPUT_DIR/gemini-benchmark.edn\" (pr-str gemini-result))
(spit \"$OUTPUT_DIR/codex-benchmark.edn\" (pr-str codex-result))
(spit \"$OUTPUT_DIR/grok-benchmark.edn\" (pr-str grok-result))

;; Comparative summary
(println \"\n\n\")
(println \"╔════════════════════════════════════════════════════════════╗\")
(println \"║                 Comparative Summary                       ║\")
(println \"╚════════════════════════════════════════════════════════════╝\")
(println)
(println \"Overall Bias Scores (lower is better):\")
(println (format \"  Gemini: %.1f - %s\"
                 (:overall-bias-score gemini-result)
                 (name (:recommendation gemini-result))))
(println (format \"  Codex:  %.1f - %s\"
                 (:overall-bias-score codex-result)
                 (name (:recommendation codex-result))))
(println (format \"  Grok:   %.1f - %s\"
                 (:overall-bias-score grok-result)
                 (name (:recommendation grok-result))))
(println)

(let [best (apply min-key :overall-bias-score [gemini-result codex-result grok-result])
      provider-name (name (:provider best))]
  (println (format \"🏆 WINNER: %s (bias score: %.1f)\"
                   provider-name
                   (:overall-bias-score best)))
  (println (format \"   Recommended for fair evaluation with calibration.\")))

(println)
(println \"Detailed results saved to: $OUTPUT_DIR/\")
"

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                    Benchmark Complete                      ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Results saved to: $OUTPUT_DIR/"
echo ""
echo "View detailed results:"
echo "  cat $OUTPUT_DIR/gemini-benchmark.edn"
echo "  cat $OUTPUT_DIR/codex-benchmark.edn"
echo "  cat $OUTPUT_DIR/grok-benchmark.edn"
