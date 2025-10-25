(ns audio.utils)

;; MIDI <-> Hz conversions
(def midi-0-hz 8.1757989156) ;; C-1

(defn hz->midi
  "Convert frequency in Hz to MIDI note number (float)"
  [hz]
  (when (pos? hz)
    (* 12 (/ (js/Math.log (/ hz midi-0-hz))
             (js/Math.log 2)))))

(defn midi->hz
  "Convert MIDI note number to frequency in Hz"
  [midi]
  (* midi-0-hz (js/Math.pow 2 (/ midi 12))))

(defn bin->midi
  "Convert FFT bin index to MIDI note number"
  [bin hz-per-bin]
  (hz->midi (* bin hz-per-bin)))

(defn midi->bin
  "Convert MIDI note number to FFT bin index"
  [midi hz-per-bin]
  (/ (midi->hz midi) hz-per-bin))

;; Note name conversions
(def chromatic-notes
  ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn midi->note-name
  "Convert MIDI number to note name (e.g., 60 -> 'C')"
  [midi]
  (get chromatic-notes (mod (js/Math.round midi) 12)))

(defn midi->note-name-full
  "Convert MIDI number to full note name with octave (e.g., 60 -> 'C4')"
  [midi]
  (let [rounded (js/Math.round midi)
        octave (- (js/Math.floor (/ rounded 12)) 1)
        note (midi->note-name rounded)]
    (str note " " octave)))

;; OKLCH color generation (replaces HSL)
(defn midi->oklch
  "Convert MIDI note to OKLCH color string.
  Uses chroma wheel position based on pitch class (C=0, C#=1, etc.)

  OKLCH is perceptually uniform:
  - L (lightness): 0-1, we use 0.65 for good visibility
  - C (chroma): 0-0.4, we use 0.15 for vibrant but not oversaturated
  - H (hue): 0-360 degrees around the color wheel"
  [midi & {:keys [l c alpha]
           :or {l 0.65 c 0.15 alpha 1.0}}]
  (let [pitch-class (mod (js/Math.round midi) 12)
        ;; Map pitch classes to hue (counterclockwise like original HSL)
        ;; C=60°, rotate through color wheel
        hue (mod (+ 60 (- 360 (* pitch-class 30))) 360)]
    (str "oklch(" l " " c " " hue " / " alpha ")")))

(defn midi->oklch-bright
  "Brighter OKLCH color for labels/text"
  [midi]
  (midi->oklch midi :l 0.75 :c 0.18))

(defn midi->oklch-dim
  "Dimmer OKLCH color for backgrounds"
  [midi]
  (midi->oklch midi :l 0.55 :c 0.12 :alpha 0.8))
