(ns lab.anki.test-occlusion
  "Test helper for creating image occlusion cards"
  (:require [lab.anki.core :as core]))

(defn normalize-rect
  "Convert pixel coordinates to normalized [0,1] coordinates.
  
  Args:
    img-width  - Image width in pixels
    img-height - Image height in pixels
    x, y       - Top-left corner in pixels
    w, h       - Width and height in pixels
  
  Returns map with :x :y :w :h as normalized floats [0,1]"
  [img-width img-height x y w h]
  {:x (/ x img-width)
   :y (/ y img-height)
   :w (/ w img-width)
   :h (/ h img-height)})

(defn make-occlusion
  "Create an occlusion region from pixel coordinates.
  
  Region spec:
    :x :y :w :h - Pixel coordinates (will be normalized)
    :answer     - Text answer for this region
    :img-width  - Image width for normalization (default 400)
    :img-height - Image height for normalization (default 300)"
  [{:keys [x y w h answer img-width img-height]
    :or {img-width 400 img-height 300}}]
  {:oid (random-uuid)
   :shape (assoc (normalize-rect img-width img-height x y w h)
                 :kind :rect
                 :normalized? true)
   :answer answer})

(def default-test-regions
  "Standard test regions for test-regions.png (400x300)"
  [{:x 50 :y 50 :w 100 :h 80 :answer "Region A"}
   {:x 200 :y 50 :w 100 :h 80 :answer "Region B"}
   {:x 50 :y 180 :w 100 :h 80 :answer "Region C"}
   {:x 200 :y 180 :w 100 :h 80 :answer "Region D"}])

(defn create-test-card
  "Create a test image occlusion card.
  
  Options map:
    :url       - Image URL (default /test-images/test-regions.png)
    :width     - Image width (default 400)
    :height    - Image height (default 300)
    :prompt    - Question prompt (default 'What is this region?')
    :regions   - Vector of region specs (default default-test-regions)
  
  Returns map with :card :hash :event"
  ([] (create-test-card {}))
  ([{:keys [url width height prompt regions]
     :or {url "/test-images/test-regions.png"
          width 400
          height 300
          prompt "What is this region?"
          regions default-test-regions}}]
   (let [;; Inject dimensions into each region for normalization
         regions-with-dims (map #(assoc % :img-width width :img-height height) regions)
         card {:type :image-occlusion
               :asset {:url url
                       :width width
                       :height height}
               :prompt prompt
               :occlusions (mapv make-occlusion regions-with-dims)}
         h (core/card-hash card)]
     {:card card
      :hash h
      :event (core/card-created-event h card)})))

(comment
  ;; Test in REPL - default card:
  (def test-data (create-test-card))
  (println "Card hash:" (:hash test-data))
  (println "Event:" (:event test-data))

  ;; Custom card with different image:
  (def custom-data
    (create-test-card
     {:url "/images/anatomy-diagram.png"
      :width 800
      :height 600
      :regions [{:x 100 :y 100 :w 150 :h 150 :answer "Heart"}
                {:x 400 :y 200 :w 150 :h 150 :answer "Lungs"}]}))

  ;; To add to your anki state:
  ;; (swap! lab.anki.ui/!state update :events conj (:event test-data))
  ;; (swap! lab.anki.ui/!state update :state core/apply-event (:event test-data))
  )
