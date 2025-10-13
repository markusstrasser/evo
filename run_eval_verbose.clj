(require '[dev.eval.core-v3 :as v3])
(require '[dev.eval.tournament :as tournament])
(require '[clojure.edn :as edn])
(require '[clojure.pprint :refer [pprint]])

(def items (edn/read-string (slurp "resources/test-architectures.edn")))

(println "=== ITEMS ===" )
(doseq [item items]
  (printf "%s: %s...\n" (:id item) (subs (:text item) 0 60)))

(println "\n=== SEED ROUND TEST ===")
(def seed-pairs (tournament/seed-round items {:min-degree 2}))
(println "Seed pairs:" seed-pairs)
(println "Count:" (count seed-pairs))

(println "\n=== EVALUATING ===")
(def result (v3/evaluate! items {:providers [:mock] :max-rounds 5}))

(println "\n=== RESULT ===")
(printf "Status: %s\n" (:status result))
(printf "Edges generated: %d\n" (count (:ranking result)))
(printf "Theta: %s\n" (:theta result))
(printf "Schema R²: %.3f\n" (:schema-r2 result))

(println "\n=== RANKING ===")
(doseq [[rank [id score]] (map-indexed vector (:ranking result))]
  (printf "  #%d  %-20s  %.2f\n" (inc rank) (name id) score))
(flush)

(println "\n✓ Evaluation complete")
(System/exit 0)
