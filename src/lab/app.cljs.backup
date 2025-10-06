(ns lab.app
  (:require [replicant.dom :as r]
            ["three" :as THREE]))

(def side-effects #{:alert :ui/prevent-default})

(def store (atom {:wave-speed 0.015
                  :wave-frequency 6.0}))

;; Three.js scene setup
(defonce scene-state (atom nil))

(defn create-pin-array []
  (let [group (THREE/Group.)
        rows 60
        cols 75
        pin-radius 0.18
        spacing 0.40
        pin-segments 16
        pins (atom [])]

    ;; Create grid of pins
    (doseq [row (range rows)
            col (range cols)]
      (let [x (- (* col spacing) (* cols spacing 0.5))
            z (- (* row spacing) (* rows spacing 0.5))

            ;; Check if within circular boundary
            nx (/ x (* cols spacing 0.5))
            nz (/ z (* rows spacing 0.5))
            dist-sq (+ (* nx nx) (* nz nz))
            dist (js/Math.sqrt dist-sq)

            ;; Initial height based on radial distance (concentric circles)
            ;; Increased amplitude for more pronounced waves
            height (if (< dist-sq 1.0)
                    (+ 2.0 (* 1.8 (js/Math.sin (* dist js/Math.PI 6))))
                    0.1)]

        (when (< dist 1.0)
          (let [geometry (THREE/CylinderGeometry. pin-radius pin-radius height pin-segments)
                material (THREE/MeshPhongMaterial. #js {:color 0xf0f0f0
                                                        :shininess 50  ;; Increased for brighter highlights
                                                        :specular 0x666666  ;; Brighter specular
                                                        :flatShading false})
                pin (THREE/Mesh. geometry material)]

            (set! (.-x (.-position pin)) x)
            (set! (.-y (.-position pin)) (/ height 2))
            (set! (.-z (.-position pin)) z)

            ;; Softer shadows
            (set! (.-castShadow pin) true)
            (set! (.-receiveShadow pin) false)

            (.add group pin)
            (swap! pins conj {:mesh pin :x x :z z :dist dist})))))

    {:group group :pins @pins}))

(defn init-three! []
  (let [container (.getElementById js/document "canvas-container")
        width (.-clientWidth container)
        height (.-clientHeight container)

        ;; Scene, camera, renderer
        scene (THREE/Scene.)
        camera (THREE/PerspectiveCamera. 42 (/ width height) 0.1 1000)
        renderer (THREE/WebGLRenderer. #js {:antialias true :alpha false})

        ;; Lighting - dark valleys, bright peaks (strong shadows not bright light)
        ambient-light (THREE/AmbientLight. 0x000000)
        dir-light1 (THREE/DirectionalLight. 0xffffff 1.8)  ;; Lower for deeper shadows
        dir-light2 (THREE/DirectionalLight. 0xffffff 0.1)  ;; Minimal fill

        ;; Pin array
        {:keys [group pins]} (create-pin-array)

        ;; Ground plane for subtle reflections
        ground-geometry (THREE/PlaneGeometry. 30 30)
        ground-material (THREE/MeshStandardMaterial. #js {:color 0x000000
                                                          :metalness 0.15
                                                          :roughness 0.7})
        ground-plane (THREE/Mesh. ground-geometry ground-material)]

    ;; Setup scene
    (set! (.-background scene) (THREE/Color. 0x000000))

    ;; Position ground
    (.rotateX ground-plane (- (/ js/Math.PI 2)))
    (set! (.-y (.-position ground-plane)) 0)
    (set! (.-receiveShadow ground-plane) true)
    (.add scene ground-plane)

    ;; Position lights - strong from top-left
    (.set (.-position dir-light1) -10 15 8)
    (set! (.-castShadow dir-light1) true)
    (.set (.-position dir-light2) 5 8 -5)

    ;; Configure shadow properties
    (set! (.-mapSize.width (.-shadow dir-light1)) 2048)
    (set! (.-mapSize.height (.-shadow dir-light1)) 2048)

    ;; Add to scene
    (.add scene ambient-light)
    (.add scene dir-light1)
    (.add scene dir-light2)
    (.add scene group)

    ;; Camera position (3/4 view matching reference)
    (set! (.-x (.-position camera)) -8)
    (set! (.-y (.-position camera)) 18)
    (set! (.-z (.-position camera)) 10)
    (.lookAt camera (.-position group))

    ;; Setup renderer
    (.setSize renderer width height)
    (set! (.-shadowMap.enabled renderer) true)
    (set! (.-shadowMap.type renderer) (.-PCFSoftShadowMap THREE))
    (.appendChild container (.-domElement renderer))

    ;; Store references
    (reset! scene-state {:scene scene
                         :camera camera
                         :renderer renderer
                         :group group
                         :pins pins
                         :time 0})

    ;; Static render (no animation - freeze at t=0 for visual matching)
    (.render renderer scene camera)))

(defn interpolate-actions [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       x))
   actions))

(defn handle-ui-event [event-data raw-actions]
  (doseq [[type payload] raw-actions]
    (case type
      :ui/prevent-default (.preventDefault (:replicant/dom-event event-data))
      :alert (js/alert (first payload))
      :update-wave-speed (swap! store assoc :wave-speed (js/parseFloat payload))
      :update-wave-frequency (swap! store assoc :wave-frequency (js/parseFloat payload))
      nil)))

(defn render-ui [state]
  [:div
   [:div#canvas-container {:style {:width "600px" :height "600px"}}]])

(add-watch store :render
           (fn [_ _ _ new-state]
             (let [root (.getElementById js/document "root")]
               (r/render root (render-ui new-state)))))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (let [dom-event (:replicant/dom-event event-data)
             enriched-actions (interpolate-actions dom-event handler-data)]
         (handle-ui-event event-data enriched-actions)))))

  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (r/render root (render-ui @store)))

  ;; Initialize Three.js after DOM is ready
  (js/setTimeout init-three! 100))
