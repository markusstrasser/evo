(ns dev.eval.async
  "Async orchestration for parallel evaluation rounds.

   Uses core.async to run multiple LLM evaluation rounds concurrently,
   reducing total latency from 3x sequential to ~1x (the slowest call)."
  (:require [clojure.core.async :as async]
            [dev.eval.core :as eval]))



;; =============================================================================
;; Parallel Evaluation

(defn evaluate-round-async
  "Async wrapper for evaluate-round that returns a channel."
  [proposals order criteria evaluator-fn]
  (async/thread
    (try
      (eval/evaluate-round proposals order criteria evaluator-fn)
      (catch Exception e
        {:error true
         :exception e
         :message (.getMessage e)}))))

(defn evaluate-proposals-parallel
  "Parallel version of evaluate-proposals using core.async.

   Runs all evaluation rounds concurrently for speed.
   Returns same format as eval.core/evaluate-proposals."
  [proposals {:keys [rounds] :as config} evaluator-fn]
  (let [;; 1. Normalize proposals
        norm-props (eval/normalize-proposals proposals (:max-length config))
        ids (vec (keys norm-props))

        ;; 2. Launch parallel evaluation rounds
        round-chans
        (doall
         (for [_ (range rounds)]
           (let [order (shuffle ids)]
             (evaluate-round-async
              norm-props
              order
              (:criteria config)
              evaluator-fn))))

        ;; 3. Collect results (blocks until all complete)
        rounds-results
        (loop [chans round-chans
               results []]
          (if (empty? chans)
            results
            (let [result (async/<!! (first chans))]
              (if (:error result)
                (throw (:exception result))
                (recur (rest chans)
                       (conj results result))))))

        ;; 4. Apply position calibration to each round
        calibrated-results
        (map (fn [round-scores]
               ;; Need to track order for each round - for now use simple impl
               ;; In full impl, evaluate-round should return {:scores {...} :order [...]}
               round-scores)
             rounds-results)

        ;; 5. Aggregate scores
        aggregated (eval/aggregate-scores calibrated-results)

        ;; 6. Rank proposals
        ranking (eval/rank-proposals aggregated)]

    {:ranking ranking
     :details aggregated
     :rounds-data calibrated-results}))

;; =============================================================================
;; Public API

(defn evaluate-parallel
  "Parallel version of eval.core/evaluate.

   Usage:
   (evaluate-parallel {:a \"proposal A\" :b \"proposal B\"}
                      {:rounds 3}
                      evaluator-fn)

   Runs evaluation rounds concurrently for faster results."
  ([proposals evaluator-fn]
   (evaluate-parallel proposals {} evaluator-fn))
  ([proposals config-overrides evaluator-fn]
   (let [config (merge eval/default-config config-overrides)]
     (evaluate-proposals-parallel proposals config evaluator-fn))))

(comment
  ;; REPL testing

  (def mock-proposals
    {:a "Short simple proposal."
     :b "Verbose proposal with lots of detail."
     :c "Medium length proposal."})

  ;; Mock evaluator with simulated delay
  (defn slow-mock-evaluator [_prompt order]
    (Thread/sleep 1000)  ; simulate API latency
    (into {}
          (map (fn [id]
                 [id (+ 5.0 (* (rand) 5.0))])
               order)))

  ;; Sequential version takes ~3 seconds
  (time (eval/evaluate mock-proposals {:rounds 3} slow-mock-evaluator))

  ;; Parallel version takes ~1 second
  (time (evaluate-parallel mock-proposals {:rounds 3} slow-mock-evaluator))
  )
