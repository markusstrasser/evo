(ns lab.anki.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab.anki.core :as core]))

(deftest parse-card-test
  (testing "QA card parsing"
    (is (= {:type :qa
            :question "What is 2+2?"
            :answer "4"}
           (core/parse-card "q What is 2+2?\na 4")))

    (is (= {:type :qa
            :question "What is the capital of France?"
            :answer "Paris"}
           (core/parse-card "q What is the capital of France?\na Paris"))))

  (testing "Cloze card parsing"
    (is (= {:type :cloze
            :template "Human DNA has [3 Billion] base pairs"
            :deletions ["3 Billion"]}
           (core/parse-card "c Human DNA has [3 Billion] base pairs")))

    (is (= {:type :cloze
            :template "The [mitochondria] is the [powerhouse] of the cell"
            :deletions ["mitochondria" "powerhouse"]}
           (core/parse-card "c The [mitochondria] is the [powerhouse] of the cell"))))

  (testing "Image occlusion card parsing"
    (is (= {:type :image-occlusion
            :alt-text "Brain diagram"
            :image-url "brain.png"
            :regions ["hippocampus" "amygdala" "cortex"]}
           (core/parse-card "![Brain diagram](brain.png) {hippocampus, amygdala, cortex}")))

    (is (= {:type :image-occlusion
            :alt-text "Noise test"
            :image-url "noise.png"
            :regions ["region1"]}
           (core/parse-card "![Noise test](noise.png) {region1}"))))

  (testing "Invalid cards"
    (is (nil? (core/parse-card "No delimiter or cloze")))))

(deftest card-hash-test
  (testing "Card hashing"
    (let [card1 {:type :qa :question "Q1" :answer "A1"}
          card2 {:type :qa :question "Q1" :answer "A1"}
          card3 {:type :qa :question "Q2" :answer "A2"}]
      (is (= (core/card-hash card1) (core/card-hash card2)))
      (is (not= (core/card-hash card1) (core/card-hash card3))))))

(deftest new-card-meta-test
  (testing "New card metadata"
    (let [meta (core/new-card-meta "test-hash")]
      (is (= "test-hash" (:card-hash meta)))
      (is (= 0 (:reviews meta)))
      (is (some? (:created-at meta)))
      (is (some? (:due-at meta))))))

(deftest schedule-card-test
  (testing "Card scheduling (mock algorithm)"
    (let [initial-meta {:card-hash "test"
                        :reviews 0
                        :created-at #?(:clj (java.util.Date.) :cljs (js/Date.))
                        :due-at #?(:clj (java.util.Date.) :cljs (js/Date.))}]

      (testing "Forgot rating"
        (let [result (core/schedule-card initial-meta :forgot)]
          (is (= :forgot (:last-rating result)))
          (is (= 1 (:reviews result)))))

      (testing "Hard rating"
        (let [result (core/schedule-card initial-meta :hard)]
          (is (= :hard (:last-rating result)))
          (is (= 1 (:reviews result)))))

      (testing "Good rating"
        (let [result (core/schedule-card initial-meta :good)]
          (is (= :good (:last-rating result)))
          (is (= 1 (:reviews result)))))

      (testing "Easy rating"
        (let [result (core/schedule-card initial-meta :easy)]
          (is (= :easy (:last-rating result)))
          (is (= 1 (:reviews result))))))))

(deftest event-creation-test
  (testing "Event creation"
    (let [review-ev (core/review-event "hash123" :good)]
      (is (= :review (:event/type review-ev)))
      (is (= "hash123" (get-in review-ev [:event/data :card-hash])))
      (is (= :good (get-in review-ev [:event/data :rating])))
      (is (some? (:event/timestamp review-ev))))

    (let [created-ev (core/card-created-event "hash456" {:type :qa})]
      (is (= :card-created (:event/type created-ev)))
      (is (= "hash456" (get-in created-ev [:event/data :card-hash])))
      (is (= {:type :qa} (get-in created-ev [:event/data :card]))))))

(deftest apply-event-test
  (testing "Applying events to state"
    (let [initial-state {:cards {} :meta {} :log []}
          card {:type :qa :question "Q" :answer "A"}
          card-hash (core/card-hash card)]

      (testing "Card creation event"
        (let [event (core/card-created-event card-hash card)
              new-state (core/apply-event initial-state event)]
          (is (= card (get-in new-state [:cards card-hash])))
          (is (some? (get-in new-state [:meta card-hash])))
          (is (= 0 (get-in new-state [:meta card-hash :reviews])))))

      (testing "Review event"
        (let [create-event (core/card-created-event card-hash card)
              state-with-card (core/apply-event initial-state create-event)
              review-ev (core/review-event card-hash :good)
              state-after-review (core/apply-event state-with-card review-ev)]
          (is (= 1 (get-in state-after-review [:meta card-hash :reviews])))
          (is (= :good (get-in state-after-review [:meta card-hash :last-rating]))))))))

(deftest reduce-events-test
  (testing "Event log reduction"
    (let [card1 {:type :qa :question "Q1" :answer "A1"}
          card2 {:type :qa :question "Q2" :answer "A2"}
          hash1 (core/card-hash card1)
          hash2 (core/card-hash card2)
          events [(core/card-created-event hash1 card1)
                  (core/card-created-event hash2 card2)
                  (core/review-event hash1 :good)]]
      (let [state (core/reduce-events events)]
        (is (= 2 (count (:cards state))))
        (is (= 1 (get-in state [:meta hash1 :reviews])))
        (is (= 0 (get-in state [:meta hash2 :reviews])))))))

(deftest due-cards-test
  (testing "Finding due cards"
    (let [card {:type :qa :question "Q" :answer "A"}
          hash (core/card-hash card)
          past-date #?(:clj (java.util.Date. 0) :cljs (js/Date. 0))
          future-date #?(:clj (java.util.Date. (+ (System/currentTimeMillis) 86400000))
                         :cljs (js/Date. (+ (.getTime (js/Date.)) 86400000)))
          state {:cards {hash card}
                 :meta {hash {:card-hash hash
                              :due-at past-date
                              :reviews 0}}}]
      (is (= [hash] (core/due-cards state)))

      (let [state-future (assoc-in state [:meta hash :due-at] future-date)]
        (is (empty? (core/due-cards state-future)))))))

(deftest card-with-meta-test
  (testing "Getting card with metadata"
    (let [card {:type :qa :question "Q" :answer "A"}
          hash (core/card-hash card)
          meta {:card-hash hash :reviews 1}
          state {:cards {hash card}
                 :meta {hash meta}}]
      (let [result (core/card-with-meta state hash)]
        (is (= :qa (:type result)))
        (is (= "Q" (:question result)))
        (is (= meta (:meta result)))))))
