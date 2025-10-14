#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.edn :as edn])

(def items-file "research/results/srs-architecture-2025-10-05-19-50/items.edn")
(def base-dir "research/results/srs-architecture-2025-10-05-19-50/")

;; Load items
(def items (-> items-file slurp edn/read-string))

;; Read full text for each proposal
(def proposals
  (mapv (fn [{:keys [id provider file]}]
          {:id id
           :provider provider
           :text (slurp (str base-dir file))})
        items))

(println "Loaded proposals:")
(doseq [{:keys [id provider text]} proposals]
  (println (format "  %s (%s): %d chars" id provider (count text))))

;; Print items for evaluator
(spit "research/results/srs-architecture-2025-10-05-19-50/eval-items.edn"
      (pr-str (mapv (fn [{:keys [id text]}]
                      {:id id :text text})
                    proposals)))

(println "\nWritten eval-items.edn for evaluator")
