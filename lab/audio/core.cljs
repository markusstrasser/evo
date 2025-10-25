(ns audio.core
  "Web Audio API integration for polyphonic pitch detection.

  Handles:
  - AudioContext setup (requires user gesture in modern browsers)
  - Microphone access via getUserMedia
  - FFT analysis via AnalyserNode
  - Energy computation and bin data management"
  (:require [audio.utils :as utils]))

;; Web Audio API setup
(defn create-audio-context
  "Create AudioContext with vendor prefixes"
  []
  (or (js/AudioContext.)
      (js/webkitAudioContext.)))

(defn create-analyser
  "Create and configure AnalyserNode"
  [audio-ctx & {:keys [fft-size min-decibels]
                :or {fft-size 16384
                     min-decibels -80}}]
  (let [analyser (.createAnalyser audio-ctx)]
    (set! (.-fftSize analyser) fft-size)
    (set! (.-minDecibels analyser) min-decibels)
    analyser))

(defn request-microphone
  "Request microphone access via getUserMedia.
  Returns promise that resolves to MediaStream.

  Modern browsers require:
  - HTTPS context
  - User gesture (button click, etc.)"
  []
  (if (and js/navigator (.-mediaDevices js/navigator))
    (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
    (js/Promise.reject
     (js/Error. "getUserMedia not supported. Use HTTPS and modern browser."))))

(defn connect-microphone!
  "Connect microphone stream to analyser node"
  [audio-ctx analyser stream]
  (let [source (.createMediaStreamSource audio-ctx stream)]
    (.connect source analyser)
    source))

;; FFT bin data management
(defn create-bin-data
  "Create initial bin data structure with static properties"
  [bin-count sample-rate fft-size]
  (let [hz-per-bin (/ sample-rate fft-size)]
    (mapv
     (fn [bin-nr]
       (let [midi (utils/bin->midi bin-nr hz-per-bin)]
         {:bin-nr bin-nr
          :midi midi
          :hz (* bin-nr hz-per-bin)
          :note (utils/midi->note-name midi)
          :note-full (utils/midi->note-name-full midi)
          :color (utils/midi->oklch midi)
          :energy-rel 0.0
          :energy-abs 0}))
     (range bin-count))))

(defn update-energy-values!
  "Update energy values in bin data from FFT analysis.
  Mutates the frequency-data Uint8Array and returns updated bin-data."
  [analyser bin-data frequency-data display-absolute?]
  (let [bin-count (count bin-data)]
    ;; Get latest FFT data
    (.getByteFrequencyData analyser frequency-data)

    ;; Find highest energy (for relative normalization)
    (let [highest-energy (if display-absolute?
                           255
                           (reduce
                            (fn [max-e idx]
                              (js/Math.max max-e (aget frequency-data idx)))
                            0
                            (range bin-count)))]

      ;; Update bin data with new energy values
      (mapv
       (fn [bin]
         (let [bin-nr (:bin-nr bin)
               energy-abs (aget frequency-data bin-nr)
               energy-rel (/ energy-abs (+ highest-energy 0.001))] ;; avoid divide-by-zero
           (assoc bin
                  :energy-abs energy-abs
                  :energy-rel energy-rel)))
       bin-data))))

;; Main audio state atom
(defonce audio-state
  (atom {:audio-ctx nil
         :analyser nil
         :source nil
         :stream nil
         :bin-data []
         :frequency-data nil
         :sample-rate 48000
         :fft-size 16384
         :bin-count 500  ;; covers up to MIDI 88 (piano range)
         :display-absolute? true
         :permission-granted? false
         :error nil}))

(defn init-audio!
  "Initialize audio context and analyser (does NOT request mic yet).
  Call this early, but request-permission! must be called from user gesture."
  []
  (try
    (let [audio-ctx (create-audio-context)
          sample-rate (.-sampleRate audio-ctx)
          fft-size 16384
          analyser (create-analyser audio-ctx :fft-size fft-size)
          bin-count 500
          frequency-data (js/Uint8Array. (.-frequencyBinCount analyser))
          bin-data (create-bin-data bin-count sample-rate fft-size)]

      (swap! audio-state assoc
             :audio-ctx audio-ctx
             :analyser analyser
             :sample-rate sample-rate
             :fft-size fft-size
             :frequency-data frequency-data
             :bin-data bin-data
             :bin-count bin-count
             :error nil)

      :initialized)
    (catch js/Error e
      (swap! audio-state assoc :error (str "Init failed: " (.-message e)))
      :error)))

(defn request-permission!
  "Request microphone permission and start audio.
  MUST be called from user gesture (button click, etc.).

  Returns promise that resolves to :granted or :denied"
  []
  (let [{:keys [audio-ctx analyser]} @audio-state]
    (if (and audio-ctx analyser)
      (-> (request-microphone)
          (.then (fn [stream]
                   ;; Resume AudioContext (required by Chrome autoplay policy)
                   (.resume audio-ctx)
                   ;; Connect mic to analyser
                   (let [source (connect-microphone! audio-ctx analyser stream)]
                     (swap! audio-state assoc
                            :stream stream
                            :source source
                            :permission-granted? true
                            :error nil)
                     :granted)))
          (.catch (fn [error]
                    (swap! audio-state assoc
                           :error (str "Mic access denied: " (.-message error))
                           :permission-granted? false)
                    :denied)))
      (js/Promise.reject
       (js/Error. "Audio not initialized. Call init-audio! first.")))))

(defn get-current-bin-data
  "Get current FFT bin data with updated energy values"
  []
  (let [{:keys [analyser bin-data frequency-data display-absolute?]} @audio-state]
    (when (and analyser bin-data frequency-data)
      (update-energy-values! analyser bin-data frequency-data display-absolute?))))

(defn toggle-display-mode!
  "Toggle between absolute and relative energy display"
  []
  (swap! audio-state update :display-absolute? not))

(defn cleanup!
  "Stop audio stream and cleanup resources"
  []
  (when-let [stream (:stream @audio-state)]
    (doseq [track (.getTracks stream)]
      (.stop track)))
  (swap! audio-state assoc
         :stream nil
         :source nil
         :permission-granted? false))
