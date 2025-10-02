(ns dev.eval.debias
  "Online bias detection and correction using anchoring vignettes.
   Learns position, recency, and provenance biases from known-outcome pairs.")

(defn inject-vignettes
  "Inject vignette pairs into scheduled comparisons at specified rate.
   Vignettes are pairs with known ground truth for bias detection."
  [pairs rate vignette-pool]
  (let [n-pairs (count pairs)
        n-vignettes (Math/ceil (* n-pairs rate))
        selected-vignettes (take n-vignettes (shuffle vignette-pool))]
    (concat pairs selected-vignettes)))

(defn estimate-bias-beta
  "Estimate bias coefficients from vignette edges.
   Returns {:position {:left β :right β} :recency {...} :prov {...}}."
  [edges]
  (let [vignette-edges (filter #(get-in % [1 :flags :vignette?]) edges)
        ;; Position bias: compare left vs right win rates
        left-wins (count (filter #(= :left (get-in % [1 :verdict])) vignette-edges))
        right-wins (count (filter #(= :right (get-in % [1 :verdict])) vignette-edges))
        total (+ left-wins right-wins)
        ;; Recency bias: compare NEW vs OLD tag win rates
        new-tagged (filter #(or (= "NEW" (get-in % [1 :flags :left-tag]))
                                (= "NEW" (get-in % [1 :flags :right-tag])))
                           vignette-edges)
        new-wins (count (filter (fn [e]
                                  (let [verdict (get-in e [1 :verdict])
                                        new-pos (cond
                                                  (= "NEW" (get-in e [1 :flags :left-tag])) :left
                                                  (= "NEW" (get-in e [1 :flags :right-tag])) :right
                                                  :else nil)]
                                    (= verdict new-pos)))
                                new-tagged))
        old-wins (- (count new-tagged) new-wins)]
    {:position {:left (if (pos? total) (- (/ left-wins total) 0.5) 0.0)
                :right (if (pos? total) (- (/ right-wins total) 0.5) 0.0)}
     :recency {:NEW (if (pos? (count new-tagged))
                      (- (/ new-wins (count new-tagged)) 0.5)
                      0.0)
               :OLD (if (pos? (count new-tagged))
                      (- (/ old-wins (count new-tagged)) 0.5)
                      0.0)}
     :prov {}})) ; Provenance bias TBD

(defn apply-correction
  "Apply bias correction to edges on logit scale before BT fit.
   Adjusts outcomes based on estimated bias coefficients."
  [edges beta]
  (map (fn [[edge-key edge-data]]
         (let [pos-bias (get-in beta [:position :left] 0.0)
               rec-bias (get-in beta [:recency :NEW] 0.0)
               ;; Simplified: adjust verdict probabilities
               adjustment (+ pos-bias rec-bias)]
           [edge-key (assoc edge-data :bias-adjustment adjustment)]))
       edges))
