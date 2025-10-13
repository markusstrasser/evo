(require '[dev.eval.core-v3 :refer [evaluate!]])
(require '[clojure.edn :as edn])
(require '[clojure.pprint :refer [pprint]])

(def items (edn/read-string (slurp "resources/items.edn")))
(println "Items:")
(pprint items)

(println "\nEvaluating" (count items) "refactoring suggestions...")
(println "Config: {:providers [:mock] :max-rounds 2}\n")

(try
  (def result (evaluate! items {:providers [:mock] :max-rounds 2}))
  (println "\n========== RESULT ==========")
  (pprint result)
  (catch Exception e
    (println "ERROR:" (.getMessage e))
    (.printStackTrace e)))

(System/exit 0)
