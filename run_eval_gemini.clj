(require '[dev.eval.core-v3 :refer [evaluate!]])
(require '[clojure.edn :as edn])
(require '[clojure.pprint :refer [pprint]])

(def items (edn/read-string (slurp "resources/test-architectures.edn")))

(println "=== Evaluating 5 architecture patterns with Gemini ===\n")
(doseq [[idx item] (map-indexed vector items)]
  (printf "%d. %s\n" (inc idx) (name (:id item))))

(println "\nRunning tournament (this will take ~30 seconds)...\n")

(def result (evaluate! items {:providers [:gemini25-pro] :max-rounds 3}))

(printf "\n========== RESULTS ==========\n")
(printf "Status: %s\n" (:status result))
(printf "Schema R²: %.3f\n" (:schema-r2 result))
(printf "Tau (stability): %.3f\n" (:tau-split result))

(println "\n=== FINAL RANKING ===")
(doseq [[rank [id score]] (map-indexed vector (:ranking result))]
  (printf "  #%d  %-20s  score: %.2f\n" (inc rank) (name id) score))
(flush)

(println "\n✓ Evaluation complete with real LLM judge")
(System/exit 0)
