(ns lab.anki.occlusion-creator-test
  (:require [clojure.test :refer [deftest is testing]]
            [lab.anki.occlusion-creator :as creator]
            [lab.anki.core :as core]))

(deftest test-initial-state
  (testing "Initial creator state"
    (creator/reset-creator!)
    (let [state @creator/!creator-state]
      (is (nil? (:image-url state)) "Image URL should be nil initially")
      (is (empty? (:occlusions state)) "Occlusions should be empty initially")
      (is (= "What is this region?" (:prompt state)) "Default prompt should be set")
      (is (false? (:drawing? state)) "Should not be drawing initially")
      (is (nil? (:current-rect state)) "Current rect should be nil"))))

(deftest test-normalize-rect
  (testing "Rectangle normalization"
    (let [norm-rect (creator/normalize-rect 400 300 100 50 80 60)]
      (is (= 0.25 (:x norm-rect)) "X should be normalized to 0.25")
      (is (< (Math/abs (- 0.1667 (:y norm-rect))) 0.001) "Y should be ~0.1667")
      (is (= 0.2 (:w norm-rect)) "Width should be normalized to 0.2")
      (is (= 0.2 (:h norm-rect)) "Height should be normalized to 0.2"))))

(deftest test-add-occlusion
  (testing "Adding occlusions"
    (creator/reset-creator!)
    (swap! creator/!creator-state assoc :image-width 400 :image-height 300)

    ;; Add first occlusion
    (creator/add-occlusion! 50 50 100 80 "Region A")
    (let [occs (:occlusions @creator/!creator-state)]
      (is (= 1 (count occs)) "Should have 1 occlusion")
      (is (= "Region A" (:answer (first occs))) "Answer should be 'Region A'")
      (is (some? (:oid (first occs))) "Should have an OID")
      (is (= :rect (get-in occs [0 :shape :kind])) "Shape kind should be :rect")
      (is (true? (get-in occs [0 :shape :normalized?])) "Should be normalized"))

    ;; Add more occlusions
    (creator/add-occlusion! 200 50 100 80 "Region B")
    (creator/add-occlusion! 50 180 100 80 "Region C")
    (is (= 3 (count (:occlusions @creator/!creator-state))) "Should have 3 occlusions")))

(deftest test-remove-occlusion
  (testing "Removing occlusions"
    (creator/reset-creator!)
    (swap! creator/!creator-state assoc :image-width 400 :image-height 300)
    (creator/add-occlusion! 50 50 100 80 "Region A")
    (creator/add-occlusion! 200 50 100 80 "Region B")

    (let [oid-to-remove (get-in @creator/!creator-state [:occlusions 0 :oid])]
      (creator/remove-occlusion! oid-to-remove)
      (is (= 1 (count (:occlusions @creator/!creator-state))) "Should have 1 occlusion after removal")
      (is (= "Region B" (get-in @creator/!creator-state [:occlusions 0 :answer])) "Remaining occlusion should be Region B"))))

(deftest test-create-card-validation
  (testing "Card creation validation"
    (creator/reset-creator!)

    ;; No image
    (is (nil? (creator/create-occlusion-card)) "Should return nil without image")

    ;; Image but no occlusions
    (swap! creator/!creator-state assoc
           :image-url "data:image/png;base64,..."
           :image-width 400
           :image-height 300)
    (is (nil? (creator/create-occlusion-card)) "Should return nil without occlusions")))

(deftest test-create-valid-card
  (testing "Creating a valid occlusion card"
    (creator/reset-creator!)
    (swap! creator/!creator-state assoc
           :image-url "data:image/png;base64,..."
           :image-width 400
           :image-height 300
           :prompt "Custom prompt")
    (creator/add-occlusion! 50 50 100 80 "Test Region 1")
    (creator/add-occlusion! 200 100 150 120 "Test Region 2")

    (let [card (creator/create-occlusion-card)]
      (is (some? card) "Should return a card")
      (is (= :image-occlusion (:type card)) "Type should be :image-occlusion")
      (is (= 2 (count (:occlusions card))) "Should have 2 occlusions")
      (is (= "Custom prompt" (:prompt card)) "Prompt should be custom")
      (is (= 400 (get-in card [:asset :width])) "Width should be 400")
      (is (= 300 (get-in card [:asset :height])) "Height should be 300")
      (is (= "data:image/png;base64,..." (get-in card [:asset :url])) "URL should be set")

      ;; Check occlusion structure
      (let [first-occ (first (:occlusions card))]
        (is (some? (:oid first-occ)) "Occlusion should have OID")
        (is (map? (:shape first-occ)) "Occlusion should have shape")
        (is (= :rect (get-in first-occ [:shape :kind])) "Shape should be rectangle")
        (is (true? (get-in first-occ [:shape :normalized?])) "Shape should be normalized")
        (is (string? (:answer first-occ)) "Should have answer string")))))

(deftest test-card-hash-consistency
  (testing "Card hashing is consistent"
    (creator/reset-creator!)
    (swap! creator/!creator-state assoc
           :image-url "test.png"
           :image-width 400
           :image-height 300)
    (creator/add-occlusion! 50 50 100 80 "Region A")

    (let [card1 (creator/create-occlusion-card)
          hash1 (core/card-hash card1)
          hash2 (core/card-hash card1)]
      (is (= hash1 hash2) "Same card should produce same hash"))))

(deftest test-reset-creator
  (testing "Reset clears all state"
    (swap! creator/!creator-state assoc
           :image-url "test.png"
           :image-width 400
           :image-height 300
           :drawing? true
           :current-rect {:start-x 10 :start-y 20}
           :prompt "Custom")
    (creator/add-occlusion! 50 50 100 80 "Test")

    (creator/reset-creator!)

    (let [state @creator/!creator-state]
      (is (nil? (:image-url state)) "Image URL should be cleared")
      (is (nil? (:image-width state)) "Width should be cleared")
      (is (nil? (:image-height state)) "Height should be cleared")
      (is (empty? (:occlusions state)) "Occlusions should be empty")
      (is (false? (:drawing? state)) "Drawing should be false")
      (is (nil? (:current-rect state)) "Current rect should be nil")
      (is (= "What is this region?" (:prompt state)) "Prompt should be reset to default"))))
