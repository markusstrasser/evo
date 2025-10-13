(require '[dev.eval.core-v3 :refer [evaluate!]])
(require '[clojure.edn :as edn])

(def items (edn/read-string (slurp "resources/test-architectures.edn")))

(println "=== Testing with Grok-4 ===\n")

(def result (evaluate! items {:providers [:grok-4] :max-rounds 2}))

(printf "\n=== RANKING ===\n")
(doseq [[rank [id score]] (map-indexed vector (:ranking result))]
  (printf "  %d. %-20s  %.2f\n" (inc rank) (name id) score))
(flush)

(printf "\nStatus: %s  |  R²: %.3f  |  τ: %.3f\n"
        (:status result) (:schema-r2 result) (:tau-split result))
(println "\n✓ Grok works!")
(System/exit 0)
