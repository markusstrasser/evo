(ns dev.eval.schema-audit
  "Schema adherence audit using R² between criteria and verdicts.
   Verifies judge coherence: do criteria explain verdicts?")

(defn- criteria-diff
  "Compute criterion difference vector for an edge.
   Returns {criterion -> (left-score - right-score)}."
  [edge]
  (let [criteria (:criteria (second edge))]
    criteria))

(defn adherence-r2
  "Compute R² adherence between rubric criteria and verdicts.
   Measures how well criteria explain verdict decisions.
   Returns {:r2 r2-value :weights {criterion -> weight}}."
  [edges]
  (if (empty? edges)
    {:r2 0.0 :weights {}}
    (let [;; Extract features (criteria diffs) and outcomes (verdicts)
          data (map (fn [[edge-key edge-data]]
                      {:criteria (:criteria edge-data)
                       :outcome (if (= :left (:verdict edge-data)) 1.0 0.0)})
                    edges)
          ;; Get all criterion names
          all-criteria (into #{} (mapcat #(keys (:criteria %)) data))
          ;; Simple linear regression: y = Σ w_k * x_k
          ;; For simplicity, use correlation-based weights
          n (count data)
          mean-outcome (/ (reduce + (map :outcome data)) n)
          ;; Compute correlations between each criterion and outcome
          correlations (into {}
                             (map (fn [crit]
                                    (let [crit-vals (map #(get-in % [:criteria crit] 0.0) data)
                                          mean-crit (/ (reduce + crit-vals) n)
                                          cov (/ (reduce + (map (fn [d cv]
                                                                  (* (- cv mean-crit)
                                                                     (- (:outcome d) mean-outcome)))
                                                                data crit-vals))
                                                 n)
                                          var-crit (/ (reduce + (map #(* (- % mean-crit)
                                                                         (- % mean-crit))
                                                                     crit-vals))
                                                      n)]
                                      [crit (if (pos? var-crit) (/ cov var-crit) 0.0)]))
                                  all-criteria))
          ;; Compute R²: explained variance / total variance
          predictions (map (fn [d]
                             (reduce + (map (fn [[k w]]
                                              (* w (get-in d [:criteria k] 0.0)))
                                            correlations)))
                           data)
          ss-res (reduce + (map (fn [y pred]
                                  (* (- y pred) (- y pred)))
                                (map :outcome data)
                                predictions))
          ss-tot (reduce + (map (fn [y]
                                  (* (- y mean-outcome) (- y mean-outcome)))
                                (map :outcome data)))
          r2 (if (pos? ss-tot) (- 1.0 (/ ss-res ss-tot)) 0.0)]
      {:r2 r2
       :weights correlations})))
