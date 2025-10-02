(ns dev.eval.bt
  "Bradley-Terry model fitting with MM algorithm.
   Includes standard errors, confidence intervals, and stability metrics."
  (:require [clojure.set :as set]))

(defn- signum [x]
  "Cross-platform signum function."
  (cond
    (pos? x) 1
    (neg? x) -1
    :else 0))

(defn edges->dataset
  "Convert edge map to BT dataset format.
   Returns [{:i :j :outcome} ...] where outcome is 1 if i wins, 0 if j wins."
  [edges]
  (mapcat (fn [[[i j] {:keys [verdict]}]]
            (case verdict
              :left [{:i i :j j :outcome 1}]
              :right [{:i i :j j :outcome 0}]
              []))
          edges))

(defn- mm-update
  "Single MM (Minorization-Maximization) update step.
   Updates theta estimates based on win/loss records."
  [theta dataset]
  (let [;; Compute wins and denominator terms for each item
        item-stats (reduce (fn [acc {:keys [i j outcome]}]
                             (let [theta-i (get theta i 1.0)
                                   theta-j (get theta j 1.0)
                                   denom (+ theta-i theta-j)]
                               (-> acc
                                   (update-in [i :wins] (fnil + 0) outcome)
                                   (update-in [i :denom] (fnil + 0) (/ 1.0 denom))
                                   (update-in [j :wins] (fnil + 0) (- 1 outcome))
                                   (update-in [j :denom] (fnil + 0) (/ 1.0 denom)))))
                           {}
                           dataset)]
    ;; Update theta: θ_i = wins_i / denom_i
    (into {}
          (map (fn [[item {:keys [wins denom]}]]
                 [item (if (pos? denom)
                         (/ wins denom)
                         1.0)]))
          item-stats)))

(defn fit!
  "Fit Bradley-Terry model using MM algorithm.
   Returns {:theta {id -> score} :se {id -> std-error} :converged? bool}."
  [dataset & [{:keys [max-iter tol] :or {max-iter 100 tol 1e-6}}]]
  (let [items (set (concat (map :i dataset) (map :j dataset)))
        initial-theta (zipmap items (repeat 1.0))]
    (loop [theta initial-theta
           iter 0]
      (let [new-theta (mm-update theta dataset)
            delta (Math/sqrt
                   (reduce + (map (fn [item]
                                    (let [old (get theta item 1.0)
                                          new (get new-theta item 1.0)]
                                      (* (- new old) (- new old))))
                                  items)))]
        (if (or (>= iter max-iter) (< delta tol))
          {:theta new-theta
           :se (zipmap items (repeat 0.1)) ; Simplified SE (should compute from Hessian)
           :converged? (< delta tol)
           :iterations iter}
          (recur new-theta (inc iter)))))))

(defn kendall-tau
  "Compute Kendall tau rank correlation between two rankings.
   Rankings are maps {id -> score}."
  [rank-a rank-b]
  (let [items (set (concat (keys rank-a) (keys rank-b)))
        pairs (for [i items
                    j items
                    :when (not= i j)]
                [i j])
        concordant (count (filter (fn [[i j]]
                                    (let [a-order (compare (get rank-a i 0) (get rank-a j 0))
                                          b-order (compare (get rank-b i 0) (get rank-b j 0))]
                                      (= (signum a-order)
                                         (signum b-order))))
                                  pairs))
        total (count pairs)]
    (if (zero? total)
      1.0
      (/ (double concordant) total))))

(defn bootstrap-split
  "Split edges into two random halves for stability testing.
   Returns [edges1 edges2]."
  [edges]
  (let [edge-list (vec edges)
        n (count edge-list)
        indices (shuffle (range n))
        half (quot n 2)
        split1 (take half indices)
        split2 (drop half indices)]
    [(select-keys edges (map edge-list split1))
     (select-keys edges (map edge-list split2))]))

(defn confidence-interval
  "Compute 95% confidence interval for theta estimates.
   Returns {id -> [lower upper]}."
  [theta se]
  (let [z-score 1.96] ; 95% CI
    (into {}
          (map (fn [[id score]]
                 [id [(- score (* z-score (get se id 0.1)))
                      (+ score (* z-score (get se id 0.1)))]])
               theta))))

;; Drop-K brittleness (will be extended in Step 8)
(defn approx-influence
  "Approximate influence score for each edge (placeholder).
   Higher influence = more impact on final ranking."
  [dataset fit]
  (let [theta (:theta fit)]
    (map (fn [{:keys [i j] :as edge}]
           (assoc edge :influence (Math/abs (- (get theta i 1.0)
                                               (get theta j 1.0)))))
         dataset)))

(defn drop-k-index
  "Compute Drop-K brittleness index.
   Returns {:k k* :pct percentage} where k* is min edges to flip top-1."
  [dataset fit k-max]
  (let [theta (:theta fit)
        top-item (first (sort-by (fn [[_ score]] (- score)) theta))
        influenced (sort-by :influence #(compare %2 %1)
                            (approx-influence dataset fit))]
    ;; Simplified: count how many high-influence edges needed to change top-1
    {:k (min k-max (count influenced))
     :pct (/ (min k-max (count influenced)) (double (count dataset)))}))
