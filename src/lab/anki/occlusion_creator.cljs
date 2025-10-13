(ns lab.anki.occlusion-creator
  "Image occlusion card creator - upload image and draw rectangles")

;; State for the occlusion creator
(defonce !creator-state
  (atom {:image-url nil
         :image-width nil
         :image-height nil
         :occlusions [] ; [{:oid uuid :shape {:x y w h} :answer "label"}]
         :drawing? false
         :current-rect nil ; {:start-x :start-y :current-x :current-y}
         :prompt "What is this region?"
         :mode :hide-all-guess-one})) ; :hide-all-guess-one or :hide-one-guess-one

(defn upload-image!
  "Handle image file upload"
  [file]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader)
          (fn [e]
            (let [url (-> e .-target .-result)
                  img (js/Image.)]
              (set! (.-onload img)
                    (fn []
                      (swap! !creator-state assoc
                             :image-url url
                             :image-width (.-width img)
                             :image-height (.-height img)
                             :occlusions [])))
              (set! (.-src img) url))))
    (.readAsDataURL reader file)))

(defn normalize-rect
  "Convert canvas pixel coordinates to normalized [0,1] coordinates"
  [img-width img-height x y w h]
  {:x (/ x img-width)
   :y (/ y img-height)
   :w (/ w img-width)
   :h (/ h img-height)})

(defn add-occlusion!
  "Add a new occlusion rectangle"
  [x y w h answer]
  (let [{:keys [image-width image-height]} @!creator-state
        shape (normalize-rect image-width image-height x y w h)]
    (swap! !creator-state update :occlusions conj
           {:oid (random-uuid)
            :shape (assoc shape :kind :rect :normalized? true)
            :answer answer})))

(defn remove-occlusion!
  "Remove an occlusion by oid"
  [oid]
  (swap! !creator-state update :occlusions
         (fn [occs] (vec (remove #(= oid (:oid %)) occs)))))

(defn create-occlusion-card
  "Create an image occlusion card from current state"
  []
  (let [{:keys [image-url image-width image-height occlusions prompt mode]} @!creator-state]
    (when (and image-url (seq occlusions))
      {:type :image-occlusion
       :asset {:url image-url
               :width image-width
               :height image-height}
       :prompt prompt
       :mode mode
       :occlusions occlusions})))

(defn reset-creator!
  "Reset creator state"
  []
  (reset! !creator-state {:image-url nil
                          :image-width nil
                          :image-height nil
                          :occlusions []
                          :drawing? false
                          :current-rect nil
                          :prompt "What is this region?"
                          :mode :hide-all-guess-one}))
