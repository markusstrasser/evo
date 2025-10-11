(ns lab.anki.test-occlusion
  "Test helper for creating image occlusion cards"
  (:require [lab.anki.core :as core]))

(defn create-test-card
  "Create a test image occlusion card for the test-regions.png image"
  []
  (let [card {:type :image-occlusion
              :asset {:url "/test-images/test-regions.png"
                      :width 400
                      :height 300}
              :prompt "What is this region?"
              :occlusions [{:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.125    ;; 50/400
                                    :y 0.167    ;; 50/300
                                    :w 0.25     ;; 100/400
                                    :h 0.267}   ;; 80/300
                            :answer "Region A"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.5      ;; 200/400
                                    :y 0.167    ;; 50/300
                                    :w 0.25     ;; 100/400
                                    :h 0.267}   ;; 80/300
                            :answer "Region B"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.125    ;; 50/400
                                    :y 0.6      ;; 180/300
                                    :w 0.25     ;; 100/400
                                    :h 0.267}   ;; 80/300
                            :answer "Region C"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.5      ;; 200/400
                                    :y 0.6      ;; 180/300
                                    :w 0.25     ;; 100/400
                                    :h 0.267}   ;; 80/300
                            :answer "Region D"}]}
        h (core/card-hash card)]
    {:card card
     :hash h
     :event (core/card-created-event h card)}))

(comment
  ;; Test in REPL:
  (def test-data (create-test-card))
  (println "Card hash:" (:hash test-data))
  (println "Event:" (:event test-data))

  ;; To add to your anki state:
  ;; (swap! lab.anki.ui/!state update :events conj (:event test-data))
  ;; (swap! lab.anki.ui/!state update :state core/apply-event (:event test-data))
  )
