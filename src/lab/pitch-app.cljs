(ns lab.pitch-app
  "Polyphonic pitch detector demo app"
  (:require [replicant.dom :as r]
            [audio.core :as audio]
            [audio.pitch :as pitch]
            [audio.visualize :as viz]
            [audio.utils :as utils]
            [audio.test-data]))

(def side-effects #{:ui/prevent-default})

(defonce store
  (atom {:permission-granted? false
         :audio-ready? false
         :display-absolute? true
         :start-midi 48  ;; C3
         :end-midi 78    ;; F#5
         :detected-notes []
         :detected-midis []
         :chord-info nil
         :error nil
         :paused? false
         :frame-buffer []  ;; for smoothing chord detection
         :last-chord nil}))

(defonce canvas-state (atom nil))
(defonce animation-frame (atom nil))

(defn interpolate-actions [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       :event/target.valueAsNumber (.. event -target -valueAsNumber)
       x))
   actions))

(defn handle-ui-event [event-data raw-actions]
  (doseq [[type & payload] raw-actions]
    (case type
      :ui/prevent-default (.preventDefault (:replicant/dom-event event-data))

      :init-audio
      (do
        (audio/init-audio!)
        (swap! store assoc :audio-ready? true))

      :request-permission
      (-> (audio/request-permission!)
          (.then (fn [result]
                   (if (= result :granted)
                     (do
                       (swap! store assoc
                              :permission-granted? true
                              :error nil)
                       (start-animation-loop!))
                     (swap! store assoc
                            :permission-granted? false
                            :error "Microphone permission denied"))))
          (.catch (fn [error]
                    (swap! store assoc
                           :error (str "Audio error: " (.-message error))))))

      :toggle-pause
      (swap! store update :paused? not)

      :toggle-display-mode
      (do
        (audio/toggle-display-mode!)
        (swap! store update :display-absolute? not))

      :update-start-midi
      (swap! store assoc :start-midi (first payload))

      :update-end-midi
      (swap! store assoc :end-midi (first payload))

      :load-test-data
      (let [test-key (keyword (first payload))]
        (when-let [test-data (audio.test-data/make-test-data test-key)]
          (swap! store assoc
                 :test-mode? true
                 :test-data test-data)))

      :exit-test-mode
      (swap! store dissoc :test-mode? :test-data)

      nil)))

(defn detect-and-update!
  "Run pitch detection and update store"
  []
  (let [bin-data (if (:test-mode? @store)
                   (:test-data @store)
                   (audio/get-current-bin-data))]
    (when bin-data
      (when-let [result (pitch/find-polyphonic bin-data :legit-energy 0.25)]
        (let [{:keys [legit-notes legit-midis]} result
              chord-info (when (seq legit-notes)
                          (pitch/detect-chord legit-notes))]

          ;; Simple buffering for chord display stability
          (swap! store
                 (fn [s]
                   (let [buffer (conj (:frame-buffer s []) legit-notes)
                         buffer (vec (take-last 4 buffer))
                         ;; Show chord if stable across frames
                         stable? (or (<= (count legit-notes) 3)
                                     (apply = buffer))
                         display-chord (when (and stable? chord-info)
                                        chord-info)]
                     (assoc s
                            :detected-notes legit-notes
                            :detected-midis legit-midis
                            :chord-info (or display-chord (:last-chord s))
                            :last-chord (or display-chord (:last-chord s))
                            :frame-buffer buffer))))))))

(defn render-canvas!
  "Render one frame to canvas"
  []
  (let [{:keys [start-midi end-midi detected-midis chord-info test-mode? test-data]} @store]
    (when-let [{:keys [canvas ctx]} @canvas-state]
      (when-let [bin-data (if test-mode? test-data (audio/get-current-bin-data))]
        (viz/render-frame! ctx canvas bin-data detected-midis chord-info
                          start-midi end-midi)))))

(defn animation-loop []
  (when-not (:paused? @store)
    (detect-and-update!)
    (render-canvas!))
  (reset! animation-frame (js/requestAnimationFrame animation-loop)))

(defn start-animation-loop! []
  (when @animation-frame
    (js/cancelAnimationFrame @animation-frame))
  (animation-loop))

(defn init-canvas! []
  (when-let [canvas (.getElementById js/document "pitch-canvas")]
    (when-let [container (.getElementById js/document "canvas-container")]
      (let [width (.-clientWidth container)
            height (.-clientHeight container)
            ctx (.getContext canvas "2d")]
        (set! (.-width canvas) width)
        (set! (.-height canvas) height)
        (reset! canvas-state {:canvas canvas :ctx ctx})))))

(defn render-ui [state]
  (let [{:keys [permission-granted? audio-ready? error paused?
                display-absolute? start-midi end-midi chord-info]} state
        [chord-root chord-type] chord-info
        chord-text (when chord-root
                    (str (utils/midi->note-name chord-root) " "
                         (get pitch/chord-abbreviations chord-type "")))]
    [:div {:style {:font-family "sans-serif"
                   :padding "20px"
                   :background "#060606"
                   :color "white"
                   :min-height "100vh"}}

     [:h1 {:style {:margin-bottom "10px"}} "Polyphonic Pitch Detector"]

     [:p {:style {:color "#888" :font-size "14px"}}
      "OKLCH colors · Web Audio API · ClojureScript"]

     ;; Test Mode Controls
     [:div {:style {:margin "15px 0"
                    :padding "10px"
                    :background "#1a1a1a"
                    :border-radius "5px"}}
      [:h3 {:style {:margin-top "0" :font-size "16px"}} "Test Mode (No Mic Required)"]
      [:div {:style {:display "flex" :gap "5px" :flex-wrap "wrap"}}
       (for [test-key [:c-major :d-minor :g-dominant-7 :single-note :octave :fifth]]
         ^{:key test-key}
         [:button {:on {:click [[:load-test-data (name test-key)]]}
                   :style {:padding "5px 10px"
                           :background "#555"
                           :color "white"
                           :border "none"
                           :border-radius "3px"
                           :cursor "pointer"
                           :font-size "12px"}}
          (name test-key)])
       (when (:test-mode? state)
         [:button {:on {:click [[:exit-test-mode]]}
                   :style {:padding "5px 10px"
                           :background "#f44"
                           :color "white"
                           :border "none"
                           :border-radius "3px"
                           :cursor "pointer"
                           :font-size "12px"}}
          "Exit Test Mode"])]]

     (when error
       [:div {:style {:background "#ff4444"
                      :padding "10px"
                      :border-radius "5px"
                      :margin "10px 0"}}
        error])

     ;; Controls
     [:div {:style {:margin "20px 0"
                    :display "flex"
                    :gap "10px"
                    :align-items "center"
                    :flex-wrap "wrap"}}

      (when-not audio-ready?
        [:button {:on {:click [[:init-audio]]}
                  :style {:padding "10px 20px"
                          :font-size "16px"
                          :background "#4CAF50"
                          :color "white"
                          :border "none"
                          :border-radius "5px"
                          :cursor "pointer"}}
         "Initialize Audio"])

      (when (and audio-ready? (not permission-granted?))
        [:button {:on {:click [[:request-permission]]}
                  :style {:padding "10px 20px"
                          :font-size "16px"
                          :background "#feff00"
                          :color "black"
                          :border "none"
                          :border-radius "5px"
                          :cursor "pointer"
                          :font-weight "bold"}}
         "Start (Grant Mic Access)"])

      (when permission-granted?
        [:<>
         [:button {:on {:click [[:toggle-pause]]}
                   :style {:padding "10px 20px"
                           :font-size "16px"
                           :background (if paused? "#4CAF50" "#ff9800")
                           :color "white"
                           :border "none"
                           :border-radius "5px"
                           :cursor "pointer"}}
          (if paused? "Resume" "Pause [Space]")]

         [:button {:on {:click [[:toggle-display-mode]]}
                   :style {:padding "10px 20px"
                           :font-size "14px"
                           :background "#666"
                           :color "white"
                           :border "none"
                           :border-radius "5px"
                           :cursor "pointer"}}
          (if display-absolute? "Absolute" "Relative")]])]

     ;; MIDI Range Controls
     (when permission-granted?
       [:div {:style {:margin "20px 0"}}
        [:div {:style {:margin-bottom "10px"}}
         [:label {:style {:margin-right "10px"}}
          (str "Start: " (utils/midi->note-name-full start-midi))]
         [:input {:type "range"
                  :min 36
                  :max 88
                  :value start-midi
                  :on {:input [[:update-start-midi :event/target.valueAsNumber]]}
                  :style {:width "200px"}}]]

        [:div
         [:label {:style {:margin-right "10px"}}
          (str "End: " (utils/midi->note-name-full end-midi))]
         [:input {:type "range"
                  :min 36
                  :max 88
                  :value end-midi
                  :on {:input [[:update-end-midi :event/target.valueAsNumber]]}
                  :style {:width "200px"}}]]])

     ;; Chord Display
     (when (and permission-granted? chord-text)
       [:div {:style {:margin "20px 0"
                      :padding "20px"
                      :background (when chord-root
                                   (utils/midi->oklch chord-root :alpha 0.3))
                      :border-radius "10px"
                      :font-size "32px"
                      :font-weight "bold"
                      :text-align "center"}}
        chord-text])

     ;; Canvas Container
     [:div#canvas-container
      {:style {:width "900px"
               :height "400px"
               :margin "20px 0"
               :background "#000"
               :border "2px solid #333"
               :border-radius "5px"}}
      [:canvas#pitch-canvas]]

     ;; Instructions
     [:div {:style {:margin-top "20px"
                    :padding "15px"
                    :background "#222"
                    :border-radius "5px"
                    :font-size "13px"}}
      [:h3 {:style {:margin-top "0"}} "Instructions:"]
      [:ul
       [:li "Click 'Initialize Audio' to set up Web Audio API"]
       [:li "Click 'Start' to grant microphone access (HTTPS required)"]
       [:li "Sing or play an instrument - multiple notes detected simultaneously"]
       [:li "Adjust MIDI range to focus on specific pitch ranges"]
       [:li "Colors are OKLCH - perceptually uniform across the spectrum"]]]]))

(add-watch store :render
           (fn [_ _ _ new-state]
             (let [root (.getElementById js/document "root")]
               (when root
                 (r/render root (render-ui new-state))))))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (let [dom-event (:replicant/dom-event event-data)
             enriched-actions (interpolate-actions dom-event handler-data)]
         (handle-ui-event event-data enriched-actions)))))

  ;; Keyboard shortcuts
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (when (= (.-keyCode e) 32) ;; spacebar
                         (.preventDefault e)
                         (swap! store update :paused? not))))

  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (r/render root (render-ui @store)))

  ;; Initialize canvas after DOM is ready
  (js/setTimeout init-canvas! 100))
