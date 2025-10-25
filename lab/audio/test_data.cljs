(ns audio.test-data
  "Generate synthetic FFT data for testing pitch detection without microphone.

  Useful for:
  - Unit testing pitch detection algorithm
  - Visual verification of chord detection
  - Development without audio hardware"
  (:require [audio.utils :as utils]))

(defn generate-sine-peak
  "Generate a Gaussian-like peak centered at target frequency.
  Simulates FFT bin energy for a single sine wave."
  [bin-nr target-bin peak-energy peak-spread]
  (let [distance (js/Math.abs (- bin-nr target-bin))
        falloff (js/Math.exp (- (/ (* distance distance)
                                    (* 2 peak-spread peak-spread))))]
    (* peak-energy falloff)))

(defn midi->bin
  "Convert MIDI to bin number (helper for test data)"
  [midi sample-rate fft-size]
  (let [hz (utils/midi->hz midi)
        hz-per-bin (/ sample-rate fft-size)]
    (js/Math.round (/ hz hz-per-bin))))

(defn generate-note
  "Generate FFT energy for a single note with harmonics"
  [bin-count midi & {:keys [sample-rate fft-size energy harmonics peak-spread]
                     :or {sample-rate 48000
                          fft-size 16384
                          energy 200
                          harmonics 3
                          peak-spread 2.5}}]
  (let [fundamental-bin (midi->bin midi sample-rate fft-size)
        bins (vec (repeat bin-count 0))]

    ;; Add fundamental and harmonics
    (reduce
     (fn [bins harmonic-num]
       (let [harmonic-bin (* fundamental-bin harmonic-num)
             harmonic-energy (* energy (/ 1 harmonic-num)) ;; harmonics decay
             spread-adjusted (* peak-spread (js/Math.sqrt harmonic-num))]
         (when (< harmonic-bin bin-count)
           (mapv (fn [bin-nr bin-val]
                   (+ bin-val
                      (generate-sine-peak bin-nr harmonic-bin
                                          harmonic-energy spread-adjusted)))
                 (range bin-count)
                 bins))))
     bins
     (range 1 (inc harmonics)))))

(defn generate-chord
  "Generate FFT data for multiple notes (chord)"
  [bin-count midis & {:keys [sample-rate fft-size]
                      :or {sample-rate 48000
                           fft-size 16384}}]
  (let [note-energies (map #(generate-note bin-count %
                                          :sample-rate sample-rate
                                          :fft-size fft-size)
                          midis)]
    ;; Sum all notes
    (apply mapv + note-energies)))

(defn energy-array->bin-data
  "Convert raw energy array to bin-data structure (like audio.core creates)"
  [energy-array sample-rate fft-size]
  (let [bin-count (count energy-array)
        hz-per-bin (/ sample-rate fft-size)
        max-energy (apply max energy-array)]

    (mapv
     (fn [bin-nr]
       (let [midi (utils/bin->midi bin-nr hz-per-bin)
             energy-abs (nth energy-array bin-nr)
             energy-rel (/ energy-abs (+ max-energy 0.001))]
         {:bin-nr bin-nr
          :midi midi
          :hz (* bin-nr hz-per-bin)
          :note (utils/midi->note-name midi)
          :note-full (utils/midi->note-name-full midi)
          :color (utils/midi->oklch midi)
          :energy-rel energy-rel
          :energy-abs energy-abs}))
     (range bin-count))))

;; Test cases
(def test-cases
  "Common test scenarios"
  {:c-major [60 64 67]      ;; C E G
   :d-minor [62 65 69]      ;; D F A
   :g-dominant-7 [67 71 74 77]  ;; G B D F
   :single-note [60]        ;; C
   :octave [60 72]          ;; C C'
   :fifth [60 67]           ;; C G
   :chromatic (range 60 72) ;; Full chromatic scale
   :high-chord [76 80 83]}) ;; E5 G#5 B5

(defn make-test-data
  "Create test bin-data for a named test case"
  [test-key & {:keys [bin-count sample-rate fft-size]
               :or {bin-count 500
                    sample-rate 48000
                    fft-size 16384}}]
  (when-let [midis (test-key test-cases)]
    (let [energy-array (generate-chord bin-count midis
                                       :sample-rate sample-rate
                                       :fft-size fft-size)]
      (energy-array->bin-data energy-array sample-rate fft-size))))

(comment
  ;; Usage examples:

  ;; Generate C major chord
  (def c-major (make-test-data :c-major))

  ;; Test pitch detection
  (require '[audio.pitch :as pitch])
  (pitch/find-polyphonic c-major :legit-energy 0.2)
  ;; => {:legit-notes [0 4 7], :legit-midis [60 64 67], ...}

  ;; Detect chord
  (pitch/detect-chord [0 4 7])
  ;; => [0 "Major"]  ;; C Major

  ;; Test all cases
  (doseq [[test-name _] test-cases]
    (let [data (make-test-data test-name)
          result (pitch/find-polyphonic data :legit-energy 0.2)]
      (println test-name "=>" (:legit-notes result))))
  )
