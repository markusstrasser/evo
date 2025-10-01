(ns eval.core
  "Debiased LLM evaluation system for AI-generated proposals.

   Implements the evaluation pipeline from docs/research/50-specs/evaluator-oct1.md:
   - Length normalization (verbosity bias mitigation)
   - Multiple shuffled evaluation rounds (position bias mitigation)
   - Position score calibration
   - Median aggregation with confidence metrics
   - Ranking and tie-breaking

   Key principles:
   - Single model, multiple independent passes (no multi-agent sycophancy)
   - Algorithmic bias correction (not prompt-based 'be unbiased')
   - Pure functions with explicit data flow
   - Parallelizable evaluation rounds"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Configuration & Constants

(def default-config
  "Default evaluation configuration."
  {:max-length 500               ; chars per proposal (verbosity mitigation)
   :rounds 3                     ; evaluation passes
   :temperature 0.0              ; deterministic scoring
   :position-weights [0.9 0.95 1.0 1.05 1.10]  ; calibration for 5 positions
   :criteria [{:name "Functional Correctness" :weight 0.3}
              {:name "Complexity (Simplicity)" :weight 0.2}
              {:name "Maintainability" :weight 0.2}
              {:name "Performance & Scalability" :weight 0.2}
              {:name "Consistency/Best-Practices" :weight 0.1}]})

;; =============================================================================
;; Proposal Normalization (Length Bias Mitigation)

(defn normalize-proposal
  "Truncate proposal to max-length and append length note if truncated.
   Mitigates verbosity bias by ensuring equal visible length."
  [text max-length]
  (if (<= (count text) max-length)
    text
    (let [visible (subs text 0 max-length)
          remaining (- (count text) max-length)]
      (str visible "... [+" remaining " chars]"))))

(defn normalize-proposals
  "Normalize all proposals in the map."
  [proposals max-length]
  (into {}
        (map (fn [[id text]]
               [id (normalize-proposal text max-length)])
             proposals)))

;; =============================================================================
;; Prompt Generation

(defn format-criteria
  "Format criteria list for prompt."
  [criteria]
  (str/join "\n"
            (map (fn [{criterion-name :name}]
                   (str "- " criterion-name))
                 criteria)))

(defn format-proposals
  "Format proposals in given order for prompt."
  [proposals order]
  (str/join "\n\n"
            (map-indexed
             (fn [idx id]
               (str (inc idx) ". " (get proposals id)))
             order)))

(defn make-evaluation-prompt
  "Generate evaluation prompt for LLM judge.

   Key design choices:
   - Explicit, measurable criteria (not vague 'quality')
   - Structured JSON output (no commentary to avoid bias)
   - Same criteria each round (no drift)
   - Neutral proposal labels (just numbers)"
  [proposals order criteria]
  (str "You are a software architecture expert tasked with evaluating proposals.\n\n"
       "There are " (count order) " proposals for an API design. "
       "Evaluate each proposal on a scale of 1 to 10 (10 = best) based on:\n"
       (format-criteria criteria) "\n\n"
       "Provide a score for each proposal ID, in JSON format like "
       "{\"1\": score, \"2\": score, ...}. No extra commentary.\n\n"
       "Proposals:\n"
       (format-proposals proposals order)))

;; =============================================================================
;; Position Bias Calibration

(defn calibrate-scores
  "Apply positional bias correction to scores based on proposal order.

   Uses empirically-derived position weights to counter LLM tendency to
   favor/disfavor certain positions (e.g. first/last).

   order - list of proposal IDs in the order they appeared in prompt
   scores - map of {id -> raw-score}
   weights - vector of correction factors for positions [0.9 0.95 1.0 ...]"
  [scores order weights]
  (into {}
        (map-indexed
         (fn [idx id]
           (let [raw-score (get scores id)
                 weight (get weights idx 1.0)]  ; default 1.0 if not enough weights
             [id (* raw-score weight)]))
         order)))

;; =============================================================================
;; Score Aggregation & Statistics

(defn median
  "Calculate median of collection."
  [coll]
  (let [sorted (sort coll)
        n (count sorted)
        mid (quot n 2)]
    (if (odd? n)
      (nth sorted mid)
      (/ (+ (nth sorted mid)
            (nth sorted (dec mid)))
         2.0))))

(defn mean
  "Calculate mean of collection."
  [coll]
  (/ (double (reduce + coll))
     (count coll)))

(defn std-dev
  "Calculate standard deviation of collection."
  [coll]
  (let [avg (mean coll)
        squared-diffs (map #(Math/pow (- % avg) 2) coll)
        variance (mean squared-diffs)]
    (Math/sqrt variance)))

(defn aggregate-scores
  "Aggregate scores from multiple rounds into final ranking.

   Returns map of {proposal-id -> {:median :mean :stddev :scores :confidence}}

   Uses median (not mean) for robustness to outliers.
   Confidence based on consistency across rounds (low stddev = high confidence)."
  [rounds-results]
  ;; rounds-results is seq of maps: [{id -> score} {id -> score} ...]
  ;; Transpose to: {id -> [score1 score2 score3]}
  (let [scores-by-id (reduce
                      (fn [acc round-scores]
                        (reduce
                         (fn [acc2 [id score]]
                           (update acc2 id (fnil conj []) score))
                         acc
                         round-scores))
                      {}
                      rounds-results)]
    (into {}
          (map (fn [[id score-list]]
                 (let [med (median score-list)
                       avg (mean score-list)
                       sd (std-dev score-list)
                       confidence (cond
                                    (< sd 0.5) :high
                                    (< sd 1.0) :medium
                                    :else :low)]
                   [id {:median med
                        :mean avg
                        :stddev sd
                        :scores score-list
                        :confidence confidence}]))
               scores-by-id))))

(defn rank-proposals
  "Sort proposals by median score (descending).
   Returns ordered list of proposal IDs."
  [aggregated-scores]
  (->> aggregated-scores
       (sort-by (comp :median val) >)
       (map first)
       vec))

;; =============================================================================
;; Evaluation Orchestration

(defn evaluate-round
  "Single evaluation round.
   Calls evaluator-fn with prompt and order.

   Returns map of {proposal-id -> score}."
  [proposals order _criteria evaluator-fn]
  (let [prompt (make-evaluation-prompt proposals order _criteria)]
    ;; evaluator-fn takes prompt, returns parsed scores
    (evaluator-fn prompt order)))

(defn evaluate-proposals
  "Main evaluation pipeline.

   Takes:
   - proposals: map of {id -> text}
   - config: evaluation configuration
   - evaluator-fn: function that takes (prompt, order) and returns {id -> score}

   Returns:
   {:ranking [id1 id2 ...]  ; sorted best to worst
    :details {id -> {:median :mean :stddev :scores :confidence}}
    :rounds-data [...]}  ; raw round results for debugging"
  [proposals {:keys [max-length rounds position-weights criteria]} evaluator-fn]
  (let [;; 1. Normalize proposals
        norm-props (normalize-proposals proposals max-length)
        ids (vec (keys norm-props))

        ;; 2. Run multiple evaluation rounds in parallel
        ;; For now, sequential (parallel implementation uses core.async)
        rounds-results
        (doall
         (for [_ (range rounds)]
           (let [order (shuffle ids)
                 raw-scores (evaluate-round norm-props order criteria evaluator-fn)
                 calibrated (calibrate-scores raw-scores order position-weights)]
             calibrated)))

        ;; 3. Aggregate scores
        aggregated (aggregate-scores rounds-results)

        ;; 4. Rank proposals
        ranking (rank-proposals aggregated)]

    {:ranking ranking
     :details aggregated
     :rounds-data rounds-results}))

;; =============================================================================
;; Public API

(defn evaluate
  "Evaluate proposals and return ranked results.

   Usage:
   (evaluate {:a \"proposal text A\"
              :b \"proposal text B\"
              :c \"proposal text C\"}
             {:rounds 3}  ; optional config overrides
             evaluator-fn)

   evaluator-fn should take (prompt, order) and return {id -> score}."
  ([proposals evaluator-fn]
   (evaluate proposals {} evaluator-fn))
  ([proposals config-overrides evaluator-fn]
   (let [config (merge default-config config-overrides)]
     (evaluate-proposals proposals config evaluator-fn))))

(comment
  ;; REPL testing
  (def mock-proposals
    {:a "Short simple proposal."
     :b (apply str (repeat 100 "Verbose proposal with lots of text. "))
     :c "Medium length proposal with reasonable detail and explanation."})

  ;; Mock evaluator that returns random scores
  (defn mock-evaluator [_prompt order]
    (into {}
          (map (fn [id]
                 [id (+ 5.0 (* (rand) 5.0))])  ; scores 5-10
               order)))

  (def result (evaluate mock-proposals {:rounds 3} mock-evaluator))

  (:ranking result)
  ;; => [:c :a :b] or similar

  (:details result)
  ;; => {:a {:median 7.2 :mean 7.1 :stddev 0.3 ...} ...}
  )
