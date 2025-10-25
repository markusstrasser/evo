(ns lab.anki.occlusion-creator-ui
  "UI components for image occlusion creator"
  (:require [lab.anki.occlusion-creator :as creator]))

(defn draw-canvas!
  "Draw image and rectangles on canvas"
  [canvas state]
  (when (and canvas (:image-url state))
    (let [ctx (.getContext canvas "2d")
          img (js/Image.)
          {:keys [image-url image-width image-height occlusions current-rect drawing?]} state]
      (set! (.-onload img)
            (fn []
              ;; Set canvas size
              (set! (.-width canvas) image-width)
              (set! (.-height canvas) image-height)

              ;; Draw image
              (.drawImage ctx img 0 0)

              ;; Draw existing occlusions
              (doseq [{:keys [shape answer]} occlusions]
                (let [x (* (:x shape) image-width)
                      y (* (:y shape) image-height)
                      w (* (:w shape) image-width)
                      h (* (:h shape) image-height)]
                  ;; Draw rectangle
                  (set! (.-strokeStyle ctx) "#00ff00")
                  (set! (.-lineWidth ctx) 3)
                  (.strokeRect ctx x y w h)

                  ;; Draw label
                  (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.7)")
                  (set! (.-font ctx) "14px sans-serif")
                  (.fillText ctx answer (+ x 5) (+ y 20))))

              ;; Draw current rectangle being drawn
              (when (and drawing? current-rect)
                (let [{:keys [start-x start-y current-x current-y]} current-rect
                      x (min start-x current-x)
                      y (min start-y current-y)
                      w (js/Math.abs (- current-x start-x))
                      h (js/Math.abs (- current-y start-y))]
                  (set! (.-strokeStyle ctx) "#ffff00")
                  (set! (.-lineWidth ctx) 2)
                  (set! (.-setLineDash ctx) #js [5 5])
                  (.strokeRect ctx x y w h)
                  (set! (.-setLineDash ctx) #js [])))))
      (set! (.-src img) image-url))))

(defn get-canvas-coords
  "Get canvas-relative mouse coordinates"
  [canvas event]
  (let [rect (.getBoundingClientRect canvas)
        x (- (.-clientX event) (.-left rect))
        y (- (.-clientY event) (.-top rect))]
    {:x x :y y}))

(defn occlusion-canvas
  "Canvas component for drawing occlusions"
  [{:keys [state on-rect-complete]}]
  [:canvas#occlusion-canvas
   {:style {:border "2px solid #333"
            :cursor "crosshair"
            :display "block"}
    :on {:mousedown (fn [e]
                      (when-let [canvas (.-target e)]
                        (let [{:keys [x y]} (get-canvas-coords canvas e)]
                          (swap! creator/!creator-state assoc
                                 :drawing? true
                                 :current-rect {:start-x x :start-y y
                                                :current-x x :current-y y}))))
         :mousemove (fn [e]
                      (when (:drawing? @creator/!creator-state)
                        (when-let [canvas (.-target e)]
                          (let [{:keys [x y]} (get-canvas-coords canvas e)]
                            (swap! creator/!creator-state assoc-in [:current-rect :current-x] x)
                            (swap! creator/!creator-state assoc-in [:current-rect :current-y] y)
                            (draw-canvas! canvas @creator/!creator-state)))))
         :mouseup (fn [e]
                    (when (:drawing? @creator/!creator-state)
                      (let [rect (:current-rect @creator/!creator-state)
                            {:keys [start-x start-y current-x current-y]} rect
                            x (min start-x current-x)
                            y (min start-y current-y)
                            w (js/Math.abs (- current-x start-x))
                            h (js/Math.abs (- current-y start-y))]
                        (swap! creator/!creator-state assoc :drawing? false :current-rect nil)
                        ;; Only add if rectangle has meaningful size
                        (when (and (> w 10) (> h 10))
                          (on-rect-complete x y w h)))))}}])

(defn occlusion-list
  "List of created occlusions with edit/delete"
  [{:keys [occlusions]}]
  (when (seq occlusions)
    [:div.occlusion-list
     [:h3 "Occlusions"]
     [:div
      (for [{:keys [oid answer]} occlusions]
        ^{:key oid}
        [:div.occlusion-item
         {:style {:display "flex" :gap "10px" :margin-bottom "10px" :align-items "center"}}
         [:input {:type "text"
                  :value answer
                  :on {:change (fn [e]
                                 (let [new-answer (-> e .-target .-value)]
                                   (swap! creator/!creator-state update :occlusions
                                          (fn [occs]
                                            (mapv #(if (= oid (:oid %))
                                                     (assoc % :answer new-answer)
                                                     %)
                                                  occs)))))}}]
         [:button {:on {:click (fn [] (creator/remove-occlusion! oid))}}
          "Delete"]])]]))

(defn creator-screen
  "Main image occlusion creator screen"
  [{:keys [state on-save on-cancel]}]
  [:div.creator-screen
   {:style {:padding "20px"}}
   [:h2 "Create Image Occlusion Card"]

   ;; Upload image
   (if-not (:image-url state)
     [:div
      [:p "Upload an image to create occlusions"]
      [:input {:type "file"
               :accept "image/*"
               :on {:change (fn [e]
                              (when-let [file (-> e .-target .-files (aget 0))]
                                (creator/upload-image! file)))}}]]

     ;; Image loaded - show canvas and controls
     [:div
      ;; Mode selector
      [:div {:style {:margin-bottom "20px"}}
       [:label "Mode: "]
       [:select {:value (name (:mode state))
                 :on {:change (fn [e]
                                (let [mode (keyword (-> e .-target .-value))]
                                  (swap! creator/!creator-state assoc :mode mode)))}}
        [:option {:value "hide-all-guess-one"} "Hide All, Guess One"]
        [:option {:value "hide-one-guess-one"} "Hide One, Guess One"]]]

      [:div {:style {:margin-bottom "20px"}}
       [:label "Prompt: "]
       [:input {:type "text"
                :value (:prompt state)
                :style {:width "400px"}
                :on {:change (fn [e]
                               (let [v (-> e .-target .-value)]
                                 (swap! creator/!creator-state assoc :prompt v)))}}]]

      [:p "Draw rectangles on the image by clicking and dragging:"]
      (occlusion-canvas {:state state
                         :on-rect-complete (fn [x y w h]
                                             (creator/add-occlusion! x y w h (str "Region " (inc (count (:occlusions state))))))})

      [:div {:style {:margin-top "20px"}}
       (occlusion-list {:occlusions (:occlusions state)})]

      [:div.creator-actions
       {:style {:margin-top "20px" :display "flex" :gap "10px"}}
       [:button {:disabled (empty? (:occlusions state))
                 :on {:click on-save}}
        "Save Card"]
       [:button {:on {:click on-cancel}}
        "Cancel"]]])

   ;; Redraw canvas after render
   (js/setTimeout
    (fn []
      (when-let [canvas (js/document.getElementById "occlusion-canvas")]
        (draw-canvas! canvas @creator/!creator-state)))
    0)])
