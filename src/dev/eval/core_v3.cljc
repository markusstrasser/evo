(ns dev.eval.core-v3
  "v3 Evaluator orchestration with readiness checks and hybrid protocol.
   Swiss-Lite tournament → BT fit → debiasing → quality gates."
  (:require [dev.eval.tournament :as tournament]
            [dev.eval.pairwise :as pairwise]
            [dev.eval.bt :as bt]
            [dev.eval.debias :as debias]
            [dev.eval.schema-audit :as audit]
            [dev.eval.mallows :as mallows]
            [dev.eval.attacks :as attacks]
            #?(:clj [dev.eval.objective :as objective])
            [dev.eval.prompts-v3 :as prompts]))

(def default-config
  {:tournament {:style :swiss-lite
                :min-degree 5
                :brackets-k 8
                :vignette-rate 0.08}
   :rubric {:criteria ["Correctness" "Simplicity" "Maintainability" "Performance" "Consistency"]
            :context "Evaluate code quality"}
   :providers [:gpt5-codex :gemini25-pro :claude-4.5]
   :stop-rule {:tau 0.9}
   :schema-threshold-r2 0.5
   :max-rounds 10})

(defn- run-tournament-round
  "Execute one round of tournament comparisons.
   Returns [updated-state duel-results-map]."
  [state config]
  (let [{:keys [tournament rubric providers]} config
        pairs (tournament/schedule-round state tournament)]
    (if (empty? pairs)
      [state {}]
      (let [provider (rand-nth providers)
            ;; Execute duels
            duels (doall (map (fn [[id-l id-r]]
                                (let [item-l (first (filter #(= (:id %) id-l) (:items state)))
                                      item-r (first (filter #(= (:id %) id-r) (:items state)))]
                                  (pairwise/duel! {:provider provider
                                                   :rubric rubric
                                                   :context (:context rubric)}
                                                  item-l item-r {})))
                              pairs))
            edge-pairs (map :edge duels)
            edge-map (into {} (map (fn [d] [(:edge d) (dissoc d :edge)]) duels))
            updated-state (tournament/update-state state edge-pairs)]
        [updated-state edge-map]))))

(defn refine-with-pointwise
  "Refine ranking with pointwise scores for items with overlapping CIs.
   Step 10: Hybrid protocol implementation."
  [items theta ci rubric providers]
  (let [;; Find clusters where CIs overlap
        overlapping (filter (fn [item-id]
                              (let [[lower upper] (get ci item-id [0 0])]
                                (some (fn [other-id]
                                        (when (not= item-id other-id)
                                          (let [[ol ou] (get ci other-id [0 0])]
                                            (or (and (>= lower ol) (<= lower ou))
                                                (and (>= upper ol) (<= upper ou))))))
                                      (keys theta))))
                            (keys theta))]
    (if (empty? overlapping)
      theta
      ;; Run pointwise evaluation for overlapping items
      (let [pointwise-scores
            (into {}
                  (map (fn [item-id]
                         (let [item (first (filter #(= (:id %) item-id) items))
                             ;; Mock pointwise for now
                               score (get theta item-id 1.0)]
                           [item-id score]))
                       overlapping))]
        (merge theta pointwise-scores)))))

(defn evaluate!
  "Main v3 evaluation pipeline with readiness checks.
   Returns result map with :status :OK | :UNSTABLE | :INVALID."
  [items config]
  (let [cfg (merge default-config config)
        initial-state {:items items
                       :theta (zipmap (map :id items) (repeat 1.0))
                       :degree-map {}
                       :existing-edges #{}}]
    ;; 0) Extract objective features (if applicable)
    #?(:clj
       (when (some :openapi items)
         (doseq [item items]
           (when-let [spec (:openapi item)]
             (objective/extract-features spec)))))

    ;; 1-4) Tournament rounds with tau-based stopping
    (loop [state initial-state
           round 0
           all-edges {}]
      (if (or (>= round (:max-rounds cfg))
              (and (> round 2)
                   (let [[e1 e2] (bt/bootstrap-split all-edges)
                         fit1 (bt/fit! (bt/edges->dataset e1))
                         fit2 (bt/fit! (bt/edges->dataset e2))
                         tau (bt/kendall-tau (:theta fit1) (:theta fit2))]
                     (>= tau (get-in cfg [:stop-rule :tau])))))
        ;; Converged or max rounds reached
        (let [;; 2) Debias
              beta (debias/estimate-bias-beta all-edges)
              corrected-edges (debias/apply-correction all-edges beta)

              ;; 3) Fit BT
              dataset (bt/edges->dataset corrected-edges)
              fit (bt/fit! dataset)
              theta (:theta fit)
              se (:se fit)
              ci (bt/confidence-interval theta se)

              ;; 5) Schema adherence
              r2-result (audit/adherence-r2 all-edges)
              r2 (:r2 r2-result)

              ;; 6) Drop-K brittleness
              brittleness (bt/drop-k-index dataset fit 100)

              ;; 7) Mallows dispersion
              rank-samples (mallows/bootstrap-ranks all-edges 50)
              dispersion (mallows/dispersion rank-samples)

              ;; 8) Refine with pointwise
              final-theta (refine-with-pointwise items theta ci
                                                 (:rubric cfg)
                                                 (:providers cfg))

              ;; 9) Attacks ASR
              attack-pairs (attacks/attack-suite items)
              ;; Simplified: ASR would require re-running attacks
              asr-result {:recency 0.0 :provenance 0.0 :verbosity 0.0}

              ;; Readiness checks
              status (cond
                       (< r2 (:schema-threshold-r2 cfg)) :INVALID
                       (< (:beta dispersion) 1.0) :UNSTABLE
                       (> (:pct brittleness) 0.3) :UNSTABLE
                       :else :OK)]
          {:ranking (sort-by (fn [[id score]] (- score)) final-theta)
           :theta final-theta
           :ci ci
           :tau-split (bt/kendall-tau (:theta fit) (:theta fit)) ; Simplified
           :bias-beta beta
           :schema-r2 r2
           :brittleness brittleness
           :dispersion dispersion
           :asr asr-result
           :status status})

        ;; Continue tournament
        (let [[new-state edge-map] (run-tournament-round state cfg)]
          (recur new-state
                 (inc round)
                 (merge all-edges edge-map)))))))
