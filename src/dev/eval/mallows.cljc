(ns dev.eval.mallows
  "Mallows dispersion model for ranking consensus strength.
   Estimates β (inverse dispersion) via bootstrap resampling."
  (:require [dev.eval.bt :as bt]))

(defn- signum [x]
  "Cross-platform signum function."
  (cond
    (pos? x) 1
    (neg? x) -1
    :else 0))

(defn bootstrap-ranks
  "Generate bootstrap ranking samples by resampling edges.
   Returns list of theta maps."
  [edges n]
  (repeatedly n
              (fn []
                (let [resampled (repeatedly (count edges)
                                            #(rand-nth (vec edges)))
                      dataset (bt/edges->dataset (into {} resampled))
                      fit (bt/fit! dataset)]
                  (:theta fit)))))

(defn kendall-distance
  "Compute Kendall distance (number of discordant pairs) between two rankings."
  [rank-a rank-b]
  (let [items (set (concat (keys rank-a) (keys rank-b)))
        pairs (for [i items
                    j items
                    :when (< (compare i j) 0)]
                [i j])
        discordant (count (filter (fn [[i j]]
                                    (let [a-order (compare (get rank-a i 0) (get rank-a j 0))
                                          b-order (compare (get rank-b i 0) (get rank-b j 0))]
                                      (not= (signum a-order)
                                            (signum b-order))))
                                  pairs))]
    discordant))

(defn- mallows-nll
  "Negative log-likelihood for Mallows model.
   P(π) ∝ exp(-β * d_K(π, σ̂))."
  [beta consensus-rank sample-ranks]
  (let [distances (map #(kendall-distance consensus-rank %) sample-ranks)
        n (count sample-ranks)
        ;; NLL = β * Σd + n*log(Z_β) where Z_β is normalization (ignored for optimization)
        nll (reduce + (map #(* beta %) distances))]
    (/ nll n)))

(defn dispersion
  "Estimate Mallows β parameter via 1D search.
   Returns {:beta β :mean-tau mean-kendall-tau :consensus consensus-ranking}."
  [rank-samples]
  (let [;; Use median rank as consensus
        all-items (set (mapcat keys rank-samples))
        consensus (into {}
                        (map (fn [item]
                               (let [scores (keep #(get % item) rank-samples)
                                     median (nth (sort scores) (quot (count scores) 2))]
                                 [item median]))
                             all-items))
        ;; Compute mean Kendall tau across all pairs of samples
        sample-pairs (for [i (range (count rank-samples))
                           j (range (count rank-samples))
                           :when (< i j)]
                       [(nth rank-samples i) (nth rank-samples j)])
        taus (map (fn [[a b]] (bt/kendall-tau a b)) sample-pairs)
        mean-tau (/ (reduce + taus) (count taus))
        ;; Estimate β via grid search (simplified)
        beta-candidates (range 0.1 5.0 0.1)
        best-beta (apply min-key
                         #(mallows-nll % consensus rank-samples)
                         beta-candidates)]
    {:beta best-beta
     :mean-tau mean-tau
     :consensus consensus}))
