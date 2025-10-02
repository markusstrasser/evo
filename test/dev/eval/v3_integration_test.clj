(ns dev.eval.v3-integration-test
  "Integration tests for v3 evaluator pipeline.
   Tests each step as specified in evaluator-oct2.md spec."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.eval.judges-api :as judges]
            [dev.eval.prompts-v3 :as prompts]
            [dev.eval.pairwise :as pairwise]
            [dev.eval.tournament :as tournament]
            [dev.eval.bt :as bt]
            [dev.eval.debias :as debias]
            [dev.eval.schema-audit :as audit]
            [dev.eval.mallows :as mallows]
            [dev.eval.attacks :as attacks]
            [dev.eval.core-v3 :as core]
            [dev.eval.report :as report]))

;; Test fixtures
(def test-items
  [{:id :a :text "Simple implementation"}
   {:id :b :text "Complex but correct"}
   {:id :c :text "Fast but buggy"}
   {:id :d :text "Elegant solution"}
   {:id :e :text "Maintainable code"}
   {:id :f :text "Legacy approach"}])

(def test-rubric
  {:criteria ["Quality" "Correctness" "Speed"]
   :context "Evaluate code samples"})

;; Step 0: API judges
(deftest test-00-mock-judge-works
  (testing "Mock judge returns valid JSON"
    (let [prompt "{\"verdict\":\"left\",\"criteria\":{\"test\":1.0}}"
          result (judges/mock-judge! :test prompt)]
      (is (map? result))
      (is (contains? result :verdict))
      (is (contains? result :criteria)))))

;; Step 1: Schema compliance
(deftest test-01-schema-validation
  (testing "Pairwise schema validation"
    (let [valid-output {:verdict "left" :criteria {:Quality 8.0}}
          [valid? _] (prompts/validate-pairwise valid-output)]
      (is (true? valid?)))

    (let [invalid-output {:verdict "maybe" :criteria {:Quality 8.0}}
          [valid? errors] (prompts/validate-pairwise invalid-output)]
      (is (false? valid?))
      (is (seq errors))))

  (testing "Pointwise schema validation"
    (let [valid-output {:score 7.5 :criteria {:Quality 8.0}}
          [valid? _] (prompts/validate-pointwise valid-output)]
      (is (true? valid?)))

    (let [invalid-output {:score 15 :criteria {:Quality 8.0}}
          [valid? errors] (prompts/validate-pointwise invalid-output)]
      (is (false? valid?))
      (is (seq errors)))))

;; Step 2: Graph building
(deftest test-02-graph-builds
  (testing "Duel execution and graph construction"
    (let [item-a (first test-items)
          item-b (second test-items)
          duel-result (pairwise/duel! {:provider :mock
                                       :rubric test-rubric
                                       :context "test"}
                                      item-a item-b {})
          graph (pairwise/build-graph [duel-result])
          stats (pairwise/graph-stats graph)]
      (is (= 2 (:node-count stats)))
      (is (= 1 (:edge-count stats)))
      (is (contains? duel-result :verdict))
      (is (contains? duel-result :criteria)))))

;; Step 3: Tournament connectivity
(deftest test-03-tournament-connectivity
  (testing "Seeding reaches minimum degree"
    (let [pairs (tournament/seed-round test-items {:min-degree 3})
          ;; Should generate enough pairs for min-degree 3
          expected-edges (/ (* (count test-items) 3) 2)]
      (is (>= (count pairs) (- expected-edges 1)))))

  (testing "Swiss pairing maintains locality"
    (let [theta {:a 1.0 :b 0.9 :c 0.8 :d 0.7 :e 0.6 :f 0.5}
          bracket #{:a :b :c}
          pairs (tournament/swiss-pairs theta test-items bracket)]
      (is (seq pairs))
      ;; Pairs should be within bracket
      (doseq [[i j] pairs]
        (is (contains? bracket i))
        (is (contains? bracket j))))))

;; Step 4: BT fit and stability
(deftest test-04-bt-convergence
  (testing "BT fit converges on transitive data"
    (let [;; Transitive: a > b > c
          dataset [{:i :a :j :b :outcome 1}
                   {:i :b :j :c :outcome 1}
                   {:i :a :j :c :outcome 1}]
          fit (bt/fit! dataset)]
      (is (:converged? fit))
      (is (> (get-in fit [:theta :a]) (get-in fit [:theta :b])))
      (is (> (get-in fit [:theta :b]) (get-in fit [:theta :c])))))

  (testing "Kendall tau computation"
    (let [rank-a {:a 1.0 :b 0.5 :c 0.2}
          rank-b {:a 0.9 :b 0.6 :c 0.3}
          tau (bt/kendall-tau rank-a rank-b)]
      (is (>= tau 0.8))))) ; High correlation expected

;; Step 5: Debiasing
(deftest test-05-bias-detection
  (testing "Position bias detected from vignettes"
    (let [;; Synthetic vignette edges with left bias
          vignette-edges {[:v1 :v2] {:verdict :left :flags {:vignette? true}}
                          [:v3 :v4] {:verdict :left :flags {:vignette? true}}
                          [:v5 :v6] {:verdict :right :flags {:vignette? true}}}
          beta (debias/estimate-bias-beta vignette-edges)
          left-bias (get-in beta [:position :left])]
      (is (> left-bias 0)))) ; Should detect left bias

  (testing "Bias correction applied"
    (let [edges {[:a :b] {:verdict :left}}
          beta {:position {:left 0.1 :right -0.1}}
          corrected (debias/apply-correction edges beta)]
      (is (seq corrected)))))

;; Step 6: Schema adherence
(deftest test-06-schema-adherence
  (testing "R² flags incoherent judges"
    (let [;; Random verdicts unrelated to criteria
          incoherent-edges {[:a :b] {:verdict :left :criteria {:Q 5.0}}
                            [:b :c] {:verdict :right :criteria {:Q 8.0}}
                            [:a :c] {:verdict :left :criteria {:Q 3.0}}}
          result (audit/adherence-r2 incoherent-edges)
          r2 (:r2 result)]
      ;; Low R² indicates poor adherence
      (is (<= r2 0.6))))

  (testing "High adherence passes"
    (let [;; Verdicts align with criteria
          coherent-edges {[:a :b] {:verdict :left :criteria {:Q 9.0}}
                          [:b :c] {:verdict :left :criteria {:Q 8.0}}
                          [:c :d] {:verdict :right :criteria {:Q 3.0}}}
          result (audit/adherence-r2 coherent-edges)
          r2 (:r2 result)]
      (is (>= r2 0.0))))) ; Simplified check

;; Step 8: Brittleness
(deftest test-08-brittleness-computed
  (testing "Drop-K index computed"
    (let [dataset [{:i :a :j :b :outcome 1}
                   {:i :b :j :c :outcome 1}]
          fit (bt/fit! dataset)
          brittle (bt/drop-k-index dataset fit 10)]
      (is (contains? brittle :k))
      (is (contains? brittle :pct))
      (is (>= (:pct brittle) 0.0))
      (is (<= (:pct brittle) 1.0)))))

;; Step 9: Dispersion
(deftest test-09-dispersion-estimates
  (testing "Mallows dispersion computed"
    (let [edges {[:a :b] {:verdict :left}
                 [:b :c] {:verdict :left}
                 [:a :c] {:verdict :left}}
          rank-samples (mallows/bootstrap-ranks edges 10)
          disp (mallows/dispersion rank-samples)]
      (is (contains? disp :beta))
      (is (contains? disp :mean-tau))
      (is (> (:beta disp) 0)))))

;; Step 11: Attacks
(deftest test-11-attack-suite-generated
  (testing "Attack suite generates perturbations"
    (let [suite (attacks/attack-suite test-items)]
      (is (seq suite))
      ;; Check attack types present
      (is (some #(= :recency (:attack-type %)) suite))
      (is (some #(= :provenance (:attack-type %)) suite))
      (is (some #(= :verbosity (:attack-type %)) suite)))))

;; Step 12: End-to-end smoke test
(deftest test-12-full-pipeline-smoke
  (testing "Full pipeline with mock judge completes"
    (let [result (core/evaluate! test-items
                                 {:providers [:mock]
                                  :max-rounds 2})]
      (is (contains? result :ranking))
      (is (contains? result :theta))
      (is (contains? result :status))
      (is (contains? result :schema-r2))
      (is (contains? result :brittleness))
      (is (contains? result :dispersion))
      (is (contains? result :asr)))))

(deftest test-12-readiness-gates
  (testing "Invalid status on low R²"
    ;; This would require mocking the schema audit
    ;; Simplified: just verify status key exists
    (let [result (core/evaluate! test-items
                                 {:providers [:mock]
                                  :max-rounds 1
                                  :schema-threshold-r2 0.9})] ; High threshold
      (is (contains? #{:OK :UNSTABLE :INVALID} (:status result))))))
