(ns test.anki.fsrs-test
  "FSRS algorithm tests - ported from hashcards
   https://github.com/eudoxia0/hashcards/blob/main/src/fsrs.rs"
  (:require [clojure.test :refer [deftest is testing]]
            [anki.fsrs :as fsrs]))

;; Test utilities

(defn approx=
  "Approximate equality for floating point numbers"
  [a b]
  (< (Math/abs (- a b)) 0.01))

(defn sim-step
  "Run a single simulation step, returns map with t, s, d, i"
  [{:keys [t s d i]} grade first-review?]
  (let [;; For first review, t=0. For subsequent, t = previous t + previous interval
        new-t (if first-review? 0.0 (+ t i))

        ;; Calculate retrievability if not first review
        r (when-not first-review?
             (fsrs/retrievability i s))

        ;; Calculate new parameters
        new-s (if first-review?
                (fsrs/initial-stability grade)
                (fsrs/new-stability d s r grade))
        new-d (if first-review?
                (fsrs/initial-difficulty grade)
                (fsrs/new-difficulty d grade))

        ;; Calculate next interval
        next-i-raw (fsrs/interval 0.9 new-s)
        next-i (max 1.0 (Math/round next-i-raw))]

    {:t new-t
     :s new-s
     :d new-d
     :i next-i}))

(defn simulate
  "Simulate a series of reviews, returns vector of steps"
  [grades]
  (loop [steps []
         state {:t 0.0 :s nil :d nil :i nil}
         remaining-grades grades
         first-review? true]
    (if (empty? remaining-grades)
      steps
      (let [grade (first remaining-grades)
            new-state (sim-step state grade first-review?)
            new-steps (conj steps new-state)]
        (recur new-steps new-state (rest remaining-grades) false)))))

;; Core FSRS function tests

(deftest test-interval-equals-stability
  (testing "When R_d = 0.9, interval should equal stability"
    (let [samples 100
          start 0.1
          end 5.0
          step (/ (- end start) (dec samples))]
      (doseq [i (range samples)]
        (let [s (+ start (* i step))
              interval (fsrs/interval 0.9 s)]
          (is (approx= interval s)
              (str "For stability " s ", interval should be ~" s ", got " interval)))))))

(deftest test-initial-difficulty-of-forgetting
  (testing "D_0(1) = W[4]"
    (is (= (fsrs/initial-difficulty :forgot)
           (nth fsrs/W 4)))))

;; Simulation tests - these match the Rust test cases exactly

(deftest test-3-easy-reviews
  (testing "Three consecutive easy reviews"
    (let [grades [:easy :easy :easy]
          expected [{:t 0.0  :s 15.69  :d 3.22  :i 16.0}
                    {:t 16.0  :s 150.28 :d 2.13  :i 150.0}
                    {:t 166.0 :s 1252.22 :d 1.0  :i 1252.0}]
          actual (simulate grades)]
      (is (= (count expected) (count actual))
          "Should have 3 steps")
      (doseq [[exp act] (map vector expected actual)]
        (is (approx= (:t exp) (:t act))
            (str "Time mismatch: expected " (:t exp) ", got " (:t act)))
        (is (approx= (:s exp) (:s act))
            (str "Stability mismatch: expected " (:s exp) ", got " (:s act)))
        (is (approx= (:d exp) (:d act))
            (str "Difficulty mismatch: expected " (:d exp) ", got " (:d act)))
        (is (approx= (:i exp) (:i act))
            (str "Interval mismatch: expected " (:i exp) ", got " (:i act)))))))

(deftest test-3-good-reviews
  (testing "Three consecutive good reviews"
    (let [grades [:good :good :good]
          expected [{:t 0.0  :s 3.17  :d 5.28 :i 3.0}
                    {:t 3.0  :s 10.73 :d 5.27 :i 11.0}
                    {:t 14.0 :s 34.57 :d 5.26 :i 35.0}]
          actual (simulate grades)]
      (is (= (count expected) (count actual))
          "Should have 3 steps")
      (doseq [[exp act] (map vector expected actual)]
        (is (approx= (:t exp) (:t act)))
        (is (approx= (:s exp) (:s act)))
        (is (approx= (:d exp) (:d act)))
        (is (approx= (:i exp) (:i act)))))))

(deftest test-2-hard-reviews
  (testing "Two consecutive hard reviews"
    (let [grades [:hard :hard]
          expected [{:t 0.0 :s 1.18 :d 6.48 :i 1.0}
                    {:t 1.0 :s 1.70 :d 7.04 :i 2.0}]
          actual (simulate grades)]
      (is (= (count expected) (count actual))
          "Should have 2 steps")
      (doseq [[exp act] (map vector expected actual)]
        (is (approx= (:t exp) (:t act)))
        (is (approx= (:s exp) (:s act)))
        (is (approx= (:d exp) (:d act)))
        (is (approx= (:i exp) (:i act)))))))

(deftest test-2-forgot-reviews
  (testing "Two consecutive forgot reviews"
    (let [grades [:forgot :forgot]
          expected [{:t 0.0 :s 0.40 :d 7.19 :i 1.0}
                    {:t 1.0 :s 0.26 :d 8.08 :i 1.0}]
          actual (simulate grades)]
      (is (= (count expected) (count actual))
          "Should have 2 steps")
      (doseq [[exp act] (map vector expected actual)]
        (is (approx= (:t exp) (:t act)))
        (is (approx= (:s exp) (:s act)))
        (is (approx= (:d exp) (:d act)))
        (is (approx= (:i exp) (:i act)))))))

(deftest test-good-then-forgot
  (testing "Good followed by forgot"
    (let [grades [:good :forgot]
          expected [{:t 0.0 :s 3.17 :d 5.28 :i 3.0}
                    {:t 3.0 :s 1.06 :d 6.8  :i 1.0}]
          actual (simulate grades)]
      (is (= (count expected) (count actual))
          "Should have 2 steps")
      (doseq [[exp act] (map vector expected actual)]
        (is (approx= (:t exp) (:t act)))
        (is (approx= (:s exp) (:s act)))
        (is (approx= (:d exp) (:d act)))
        (is (approx= (:i exp) (:i act)))))))

;; High-level API tests

(deftest test-schedule-next-review-first-review
  (testing "First review with good grade"
    (let [result (fsrs/schedule-next-review {:grade :good})]
      (is (approx= (:stability result) 3.17)
          "Initial stability for 'good' should be W[2]")
      (is (approx= (:difficulty result) 5.28)
          "Initial difficulty for 'good' should match formula")
      (is (approx= (:interval result) 3.0)
          "Interval should be rounded to 3 days"))))

(deftest test-schedule-next-review-second-review
  (testing "Second review after good grade"
    (let [first-review (fsrs/schedule-next-review {:grade :good
                                                    :review-time-ms 0})
          ;; Simulate 3 days passing
          second-review (fsrs/schedule-next-review
                         {:grade :good
                          :stability (:stability first-review)
                          :difficulty (:difficulty first-review)
                          :last-review-ms 0
                          :review-time-ms (* 3 24 60 60 1000)})]
      (is (approx= (:stability second-review) 10.73)
          "Second stability should match simulation")
      (is (approx= (:difficulty second-review) 5.27)
          "Difficulty should decrease slightly")
      (is (approx= (:interval second-review) 11.0)
          "Interval should be 11 days"))))
