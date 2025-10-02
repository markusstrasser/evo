(ns eval-core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dev.eval.core :as eval]))

;; =============================================================================
;; Test Data

(def sample-proposals
  {:a "Short proposal text."
   :b "Medium length proposal with more detail and explanation."
   :c "Very long proposal with extensive detail and comprehensive coverage of all aspects."})

(defn mock-evaluator
  "Deterministic mock evaluator for testing."
  [_prompt order]
  (into {}
        (map (fn [id]
               [id (case id
                     :a 7.0
                     :b 8.0
                     :c 9.0
                     6.0)])
             order)))

;; =============================================================================
;; Normalization Tests

(deftest test-normalize-proposal
  (testing "Short text unchanged"
    (is (= "Hello" (eval/normalize-proposal "Hello" 100))))

  (testing "Long text truncated with note"
    (let [long-text (apply str (repeat 100 "x"))
          result (eval/normalize-proposal long-text 50)]
      (is (= 50 (count (subs result 0 50))))
      (is (str/includes? result "[+50 chars]"))))

  (testing "Exact length unchanged"
    (is (= "12345" (eval/normalize-proposal "12345" 5)))))

(deftest test-normalize-proposals
  (testing "Normalizes all proposals in map"
    (let [proposals {:a "short" :b (apply str (repeat 200 "x"))}
          normalized (eval/normalize-proposals proposals 100)]
      (is (= "short" (:a normalized)))
      (is (< (count (:b normalized)) 120))  ; truncated + note
      (is (str/includes? (:b normalized) "[+")))))

;; =============================================================================
;; Prompt Generation Tests

(deftest test-format-criteria
  (testing "Formats criteria list"
    (let [criteria [{:name "Correctness" :weight 0.5}
                    {:name "Simplicity" :weight 0.5}]
          result (eval/format-criteria criteria)]
      (is (str/includes? result "Correctness"))
      (is (str/includes? result "Simplicity")))))

(deftest test-format-proposals
  (testing "Formats proposals with numbers"
    (let [proposals {:a "Prop A" :b "Prop B"}
          order [:a :b]
          result (eval/format-proposals proposals order)]
      (is (str/includes? result "1. Prop A"))
      (is (str/includes? result "2. Prop B"))))

  (testing "Respects order"
    (let [proposals {:a "Prop A" :b "Prop B"}
          order [:b :a]
          result (eval/format-proposals proposals order)]
      (is (str/starts-with? result "1. Prop B")))))

(deftest test-make-evaluation-prompt
  (testing "Creates complete prompt"
    (let [proposals {:a "Design A" :b "Design B"}
          order [:a :b]
          criteria [{:name "Quality" :weight 1.0}]
          prompt (eval/make-evaluation-prompt proposals order criteria)]
      (is (str/includes? prompt "software architecture expert"))
      (is (str/includes? prompt "Quality"))
      (is (str/includes? prompt "Design A"))
      (is (str/includes? prompt "JSON format")))))

;; =============================================================================
;; Position Calibration Tests

(deftest test-calibrate-scores
  (testing "Applies position weights"
    (let [scores {:a 10.0 :b 10.0 :c 10.0}
          order [:a :b :c]
          weights [0.9 1.0 1.1]
          calibrated (eval/calibrate-scores scores order weights)]
      (is (= 9.0 (:a calibrated)))
      (is (= 10.0 (:b calibrated)))
      (is (= 11.0 (:c calibrated)))))

  (testing "Handles missing weights gracefully"
    (let [scores {:a 10.0 :b 10.0 :c 10.0 :d 10.0}
          order [:a :b :c :d]
          weights [0.9 1.0]
          calibrated (eval/calibrate-scores scores order weights)]
      (is (= 9.0 (:a calibrated)))
      (is (= 10.0 (:b calibrated)))
      (is (= 10.0 (:c calibrated)))  ; no weight, defaults to 1.0
      (is (= 10.0 (:d calibrated))))))

;; =============================================================================
;; Statistics Tests

(deftest test-median
  (testing "Odd count"
    (is (= 5 (eval/median [1 3 5 7 9]))))

  (testing "Even count"
    (is (= 5.0 (eval/median [1 3 7 9]))))

  (testing "Single value"
    (is (= 42 (eval/median [42])))))

(deftest test-mean
  (testing "Mean calculation"
    (is (= 5.0 (eval/mean [1 3 5 7 9]))))

  (testing "Single value"
    (is (= 42.0 (eval/mean [42])))))

(deftest test-std-dev
  (testing "Standard deviation"
    (let [sd (eval/std-dev [2 4 4 4 5 5 7 9])]
      (is (< 1.9 sd 2.1))))  ; approx 2.0

  (testing "No variance"
    (is (= 0.0 (eval/std-dev [5 5 5 5])))))

;; =============================================================================
;; Aggregation Tests

(deftest test-aggregate-scores
  (testing "Aggregates multiple rounds"
    (let [rounds [{:a 8.0 :b 7.0}
                  {:a 8.5 :b 7.2}
                  {:a 7.8 :b 7.1}]
          result (eval/aggregate-scores rounds)]
      (is (= 8.0 (get-in result [:a :median])))
      (is (= 7.1 (get-in result [:b :median])))
      (is (contains? (:a result) :mean))
      (is (contains? (:a result) :stddev))
      (is (contains? (:a result) :confidence))))

  (testing "Confidence levels"
    (let [low-variance [{:a 8.0} {:a 8.1} {:a 8.05}]
          high-variance [{:a 5.0} {:a 10.0} {:a 6.0}]
          low-result (eval/aggregate-scores low-variance)
          high-result (eval/aggregate-scores high-variance)]
      (is (= :high (get-in low-result [:a :confidence])))
      (is (#{:medium :low} (get-in high-result [:a :confidence]))))))

(deftest test-rank-proposals
  (testing "Ranks by median score descending"
    (let [aggregated {:a {:median 8.0}
                      :b {:median 9.0}
                      :c {:median 7.0}}
          ranking (eval/rank-proposals aggregated)]
      (is (= [:b :a :c] ranking)))))

;; =============================================================================
;; End-to-End Pipeline Tests

(deftest test-evaluate-proposals
  (testing "Complete evaluation pipeline"
    (let [proposals {:a "Proposal A" :b "Proposal B" :c "Proposal C"}
          config (merge eval/default-config {:rounds 3})
          result (eval/evaluate-proposals proposals config mock-evaluator)]

      (is (contains? result :ranking))
      (is (contains? result :details))
      (is (contains? result :rounds-data))

      (is (= 3 (count (:ranking result))))
      (is (vector? (:ranking result)))

      (is (= 3 (count (:details result))))
      (is (contains? (:details result) :a))
      (is (contains? (:details result) :b))
      (is (contains? (:details result) :c))

      (is (= 3 (count (:rounds-data result))))))

  (testing "With mock evaluator ranking"
    (let [result (eval/evaluate sample-proposals {} mock-evaluator)]
      ;; Mock evaluator assigns: a=7, b=8, c=9
      (is (= :c (first (:ranking result))))
      (is (= :a (last (:ranking result)))))))

(deftest test-evaluate-api
  (testing "Public API with defaults"
    (let [proposals {:a "A" :b "B"}
          result (eval/evaluate proposals mock-evaluator)]
      (is (contains? result :ranking))
      (is (contains? result :details))))

  (testing "Public API with config overrides"
    (let [proposals {:a "A" :b "B"}
          result (eval/evaluate proposals {:rounds 5} mock-evaluator)]
      (is (= 5 (count (:rounds-data result)))))))

;; =============================================================================
;; Edge Cases

(deftest test-edge-cases
  (testing "Single proposal"
    (let [result (eval/evaluate {:a "Only one"} {} mock-evaluator)]
      (is (= [:a] (:ranking result)))))

  (testing "Two proposals"
    (let [result (eval/evaluate {:a "First" :b "Second"} {} mock-evaluator)]
      (is (= 2 (count (:ranking result))))))

  (testing "Empty proposal text"
    (let [result (eval/evaluate {:a "" :b "Text"} {} mock-evaluator)]
      (is (= 2 (count (:ranking result))))))

  (testing "Very long proposal gets truncated"
(let [long-text (apply str (repeat 1000 "x"))
           proposals {:a long-text}
           normalized (eval/normalize-proposals proposals 100)]
      (is (< (count (:a normalized)) 150)))))

(deftest test-shuffling-produces-different-orders
  (testing "Multiple rounds produce different orderings"
    (let [ids [:a :b :c :d :e]
          orders (repeatedly 10 #(shuffle ids))]
      ;; Very unlikely all 10 shuffles are identical
      (is (> (count (set orders)) 1)))))
