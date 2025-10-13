(require '[dev.eval.core-v3 :refer [evaluate!]])
(require '[clojure.edn :as edn])
(require '[clojure.pprint :refer [pprint]])

(def items (edn/read-string (slurp "resources/items.edn")))
(println "Evaluating" (count items) "refactoring suggestions...")
(println "This will call Gemini API for comparisons...\n")

(def result (evaluate! items {:providers [:gemini25-pro] :max-rounds 5}))

(println "\n========== EVALUATION COMPLETE ==========")
(println "Status:" (:status result))
(println "\nRanking:")
(doseq [[id score] (:ranking result)]
  (printf "  %s: %.3f\n" id score))

(println "\nQuality Metrics:")
(printf "  Kendall-τ: %.3f\n" (:tau-split result))
(printf "  Schema R²: %.3f\n" (:schema-r2 result))
(printf "  Brittleness: %.1f%%\n" (* 100 (get-in result [:brittleness :pct] 0)))
(printf "  Dispersion β: %.3f\n" (get-in result [:dispersion :beta] 0))

(System/exit 0)
