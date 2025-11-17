#!/usr/bin/env bb
;; Extract critical call paths from clj-kondo analysis
;; Usage: bb scripts/call-graph.clj

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.shell :as shell])

(defn run-kondo-analysis []
  (let [result (shell/sh "clj-kondo" "--lint" "src"
                         "--config" "{:output {:analysis true :format :edn}}"
                         :env {"CLJ_KONDO_IGNORE_ERRORS" "true"})]
    (if (zero? (:exit result))
      (edn/read-string (:out result))
      (do
        (println "Warning: clj-kondo failed, using empty analysis")
        {:analysis {}}))))

(defn extract-calls [analysis]
  (let [var-definitions (get-in analysis [:analysis :var-definitions] [])
        var-usages (get-in analysis [:analysis :var-usages] [])]
    {:definitions (group-by :ns var-definitions)
     :usages (group-by :to var-usages)}))

(defn find-entry-points [definitions]
  ;; Find likely entry points (handlers, main fns, exported)
  (->> definitions
       (mapcat val)
       (filter #(or (str/includes? (str (:name %)) "handle")
                    (str/includes? (str (:name %)) "main")
                    (str/includes? (str (:name %)) "dispatch")))
       (map #(select-keys % [:ns :name]))
       (take 10)))

(defn trace-calls [fn-key usages depth]
  (when (<= depth 3)  ; Limit depth
    (let [calls (get usages fn-key [])]
      (for [call (take 5 calls)]  ; Limit breadth
        {:from fn-key
         :to (:to call)
         :name (:name call)
         :children (trace-calls (:to call) usages (inc depth))}))))

(defn -main []
  (println "# Critical Call Paths")
  (println)
  (println "Analyzing codebase with clj-kondo...")

  (let [analysis (run-kondo-analysis)
        {:keys [definitions usages]} (extract-calls analysis)
        entry-points (find-entry-points definitions)]

    (println (str "\n## Entry Points (" (count entry-points) " found)"))
    (println)

    (doseq [ep entry-points]
      (println (str "### " (:ns ep) "/" (:name ep)))
      (println)
      (let [traces (trace-calls (:ns ep) usages 0)]
        (doseq [trace (take 5 traces)]
          (println (str "- calls `" (:name trace) "` in `" (:to trace) "`"))))
      (println))

    (println "\n## Most Called Functions")
    (println)
    (let [call-counts (->> usages
                           vals
                           (apply concat)
                           (map :to)
                           frequencies
                           (sort-by val >)
                           (take 10))]
      (doseq [[ns count] call-counts]
        (println (str "- `" ns "` - called " count " times"))))))

(-main)
