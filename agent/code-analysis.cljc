;; Code analysis agent tools for productivity
;; Helps avoid dead ends by providing structural insights

(ns agent.code-analysis
  (:require [clojure.string :as str]))

;; Analyze namespace dependencies and identify potential issues
(defn analyze-namespace-health
  "Analyze a Clojure namespace for health metrics and potential issues.

  Args:
    ns-name: String name of the namespace to analyze

  Returns:
    Map with :namespace, :status (:healthy/:not-found/:error),
    :public-fns count, :dependencies count, and :potential-issues if any."
  [ns-name]
  (try
    (let [ns-sym (symbol ns-name)
          ns-obj (find-ns ns-sym)]
      (if ns-obj
        (let [publics (ns-publics ns-obj)
              refs (ns-refers ns-obj)
              aliases (ns-aliases ns-obj)
              undefined-refs (filter #(nil? (ns-resolve ns-obj %)) (keys refs))]
          {:namespace ns-name
           :status :healthy
           :public-fns (count publics)
           :dependencies (count aliases)
           :potential-issues (when (seq undefined-refs)
                              {:undefined-refs undefined-refs})})
        {:namespace ns-name :status :not-found :error "Namespace not loaded or does not exist"}))
    (catch Exception e
      {:namespace ns-name :status :error
       :error (str "Failed to analyze namespace '" ns-name "': " (.getMessage e) ". "
                   "This may indicate a corrupted namespace or classpath issue.")})))

;; Find functions that might be missing forward declarations
(defn find-potential-forward-decl-issues
  "Analyze Clojure file content for potential forward declaration issues.

  Args:
    file-content: String content of the Clojure file

  Returns:
    Map with :defined-functions count, :potentially-undefined vector,
    and :risk-level (:low/:medium/:high)."
  [file-content]
  (let [lines (str/split-lines file-content)
        function-defs (filter #(re-find #"^\(defn\s+" %) lines)
        function-calls (mapcat #(re-seq #"\(\w+" %) lines)
        called-fns (set (map #(subs % 1) function-calls))
        defined-fns (set (map #(second (re-find #"^\(defn\s+(\w+)" %)) function-defs))
        potentially-undefined (clojure.set/difference called-fns defined-fns)]
    {:defined-functions (count defined-fns)
     :potentially-undefined (vec potentially-undefined)
     :risk-level (cond
                   (> (count potentially-undefined) 5) :high
                   (> (count potentially-undefined) 2) :medium
                   :else :low)}))

;; Quick schema validation helper
(defn validate-db-structure
  "Validate database structure against required keys.

  Args:
    db: Database map to validate

  Returns:
    Map with :valid?, :missing-keys, :extra-keys, :node-count, :tx-count."
  [db]
  (let [required-keys evolver.schemas/required-db-keys
        present-keys (set (keys db))
        missing-keys (clojure.set/difference required-keys present-keys)
        extra-keys (clojure.set/difference present-keys required-keys)]
    {:valid? (empty? missing-keys)
     :missing-keys (vec missing-keys)
     :extra-keys (vec extra-keys)
     :node-count (count (:nodes db))
     :tx-count (count (:tx-log db))}))

;; Performance profiling helper
(defn profile-operation
  "Profile the performance of an operation function.

  Args:
    op-fn: Function to profile (should return a map with :status)
    iterations: Number of times to run the operation

  Returns:
    Map with :iterations, :total-time-ms, :avg-time-ms, :results-summary."
  [op-fn iterations]
  (let [start (System/currentTimeMillis)
        results (doall (repeatedly iterations op-fn))
        end (System/currentTimeMillis)
        duration (- end start)]
    {:iterations iterations
     :total-time-ms duration
     :avg-time-ms (/ duration iterations)
     :results-summary (frequencies (map :status results))
     :performance-metadata {:min-time (apply min (map :duration results))
                           :max-time (apply max (map :duration results))
                           :median-time (nth (sort (map :duration results)) (quot iterations 2))
                           :p95-time (nth (sort (map :duration results)) (int (* 0.95 iterations)))}}))