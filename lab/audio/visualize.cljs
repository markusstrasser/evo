(ns audio.visualize
  "Canvas visualization for polyphonic pitch detection.
  Uses OKLCH colors for perceptually uniform, vibrant display."
  (:require [audio.utils :as utils]))

(defn clear-canvas!
  "Clear entire canvas"
  [ctx canvas]
  (.clearRect ctx 0 0 (.-width canvas) (.-height canvas)))

(defn calc-bin-position
  "Calculate x position for a bin based on its MIDI value.
  Returns normalized x position (0-1) within start/end MIDI range."
  [midi start-midi end-midi]
  (/ (- midi start-midi)
     (- end-midi start-midi)))

(defn calc-bin-width
  "Calculate visual width for a bin.
  Uses neighboring bins to fill space evenly."
  [bin-data bin-idx]
  (let [bin (get bin-data bin-idx)
        left-bin (get bin-data (dec bin-idx))
        right-bin (get bin-data (inc bin-idx))]
    (when (and bin left-bin right-bin)
      (let [left-pos (:pos-x left-bin 0)
            right-pos (:pos-x right-bin 1)
            width (- right-pos left-pos)]
        (* width 1.9))))) ;; padding factor

(defn prepare-bin-display-data
  "Add display properties (pos-x, width) to bin data"
  [bin-data start-midi end-midi]
  (let [;; Add position to each bin
        with-pos (mapv
                  (fn [bin]
                    (assoc bin :pos-x
                           (calc-bin-position (:midi bin)
                                              (dec start-midi)
                                              (inc end-midi))))
                  bin-data)

        ;; Add width to each bin
        with-width (vec
                    (map-indexed
                     (fn [idx bin]
                       (assoc bin :width (or (calc-bin-width with-pos idx) 0.01)))
                     with-pos))]
    with-width))

(defn draw-spectrum!
  "Draw frequency spectrum as colored bars"
  [ctx canvas bin-data]
  (doseq [bin bin-data]
    (let [{:keys [pos-x width energy-rel color]} bin
          x (* pos-x (.-width canvas))
          bar-height (* energy-rel (.-height canvas))
          y (- (.-height canvas) bar-height)]

      (set! (.-fillStyle ctx) color)
      (.fillRect ctx x y width bar-height))))

(defn draw-note-label!
  "Draw note label with background circle"
  [ctx canvas midi note x y]
  (let [rect-size 60
        color (utils/midi->oklch-dim midi)
        text-color "white"]

    (when (> x 10)
      ;; Draw background circle
      (set! (.-fillStyle ctx) color)
      (.beginPath ctx)
      (.arc ctx x y (/ rect-size 2) 0 (* 2 js/Math.PI))
      (.fill ctx)

      ;; Draw text
      (set! (.-font ctx) "bold 30px Verdana")
      (set! (.-textAlign ctx) "center")
      (set! (.-textBaseline ctx) "middle")
      (set! (.-fillStyle ctx) text-color)
      (.fillText ctx note x y))))

(defn draw-detected-notes!
  "Draw labels for detected notes/chords"
  [ctx canvas bin-data detected-midis]
  (doseq [midi detected-midis]
    (when-let [bin (some #(when (= (js/Math.round (:midi %)) midi) %)
                         bin-data)]
      (let [x (* (:pos-x bin) (.-width canvas))
            y (/ (- (.-height canvas) (* (.-height canvas) (:energy-rel bin))) 2)
            note (:note bin)]
        (draw-note-label! ctx canvas midi note x y)))))

(defn draw-chord-display!
  "Draw chord name in fixed position"
  [ctx canvas chord-root chord-type]
  (let [x (/ (.-width canvas) 2)
        y 50
        color (when chord-root (utils/midi->oklch-bright chord-root))
        text (str (utils/midi->note-name chord-root) " " (or chord-type ""))]

    (when chord-root
      ;; Background
      (set! (.-fillStyle ctx) (or color "rgba(0,0,0,0.7)"))
      (.fillRect ctx (- x 100) (- y 25) 200 50)

      ;; Text
      (set! (.-font ctx) "bold 28px sans-serif")
      (set! (.-textAlign ctx) "center")
      (set! (.-textBaseline ctx) "middle")
      (set! (.-fillStyle ctx) "white")
      (.fillText ctx text x y))))

(defn render-frame!
  "Main render function for one animation frame"
  [ctx cvs bin-data detected-midis chord-info start-midi end-midi]
  (clear-canvas! ctx cvs)

  ;; Prepare display data with positions
  (let [display-data (prepare-bin-display-data bin-data start-midi end-midi)]

    ;; Draw spectrum
    (draw-spectrum! ctx cvs display-data)

    ;; Draw detected note labels
    (when detected-midis
      (draw-detected-notes! ctx cvs display-data detected-midis))

    ;; Draw chord name
    (when chord-info
      (let [[chord-root chord-type] chord-info]
        (draw-chord-display! ctx cvs chord-root chord-type)))))
