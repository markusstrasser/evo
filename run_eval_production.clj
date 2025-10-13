(require '[dev.eval.core-v3 :refer [evaluate!]])
(require '[clojure.edn :as edn])

(def items (edn/read-string (slurp "resources/test-architectures.edn")))

(println "=== Architecture Evaluation with Real LLMs ===\n")
(println "Evaluating 5 patterns:")
(doseq [[idx item] (map-indexed vector items)]
  (printf "  %d. %-20s\n" (inc idx) (name (:id item))))

(println "\nUsing judges: Grok-4, GPT-5 Codex, OpenAI GPT-4")
(println "Rounds: 4 (expect ~2 minutes)\n")

(def result (evaluate! items {:providers [:grok-4 :gpt5-codex]
                              :max-rounds 4}))

(printf "\n========== RESULTS ==========\n")
(printf "Status: %s\n\n" (:status result))

(println "=== RANKING ===")
(doseq [[rank [id score]] (map-indexed vector (:ranking result))]
  (let [ci (get (:ci result) id [0 0])
        [lower upper] ci]
    (printf "  %d. %-20s  %.2f  [%.2f - %.2f]\n"
            (inc rank) (name id) score lower upper)))

(printf "\n=== QUALITY METRICS ===\n")
(printf "  Kendall τ (stability):  %.3f\n" (:tau-split result))
(printf "  Schema R² (coherence):  %.3f\n" (:schema-r2 result))
(printf "  Dispersion β:           %.3f\n" (get-in result [:dispersion :beta] 0))
(printf "  Brittleness:            %.1f%%\n" (* 100 (get-in result [:brittleness :pct] 0)))
(flush)

(println "\n✓ Evaluation complete")
(System/exit 0)
