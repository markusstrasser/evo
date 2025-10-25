# Audio Package - Polyphonic Pitch Detection

ClojureScript port of the [polyphon](https://github.com/best/polyphon) pitch detector with improvements.

## Features

- **Polyphonic pitch detection** - Detects multiple simultaneous notes
- **Chord recognition** - Identifies common chord types (Major, Minor, 7th, etc.)
- **OKLCH colors** - Perceptually uniform color space (replaces HSL)
- **Test mode** - Synthetic audio data for testing without microphone
- **Modern Web Audio API** - Follows Chrome's autoplay policy requirements

## Modules

### `audio.core`
Web Audio API integration:
- AudioContext setup
- Microphone access (getUserMedia)
- FFT analysis via AnalyserNode
- Energy computation

### `audio.pitch`
Polyphonic pitch detection algorithm:
- Frequency bin clustering
- Harmonic analysis
- Chord detection from pitch classes

### `audio.utils`
Utility functions:
- Hz ↔ MIDI conversions
- Note name formatting
- OKLCH color generation

### `audio.visualize`
Canvas visualization:
- Spectrum display
- Note labels with colors
- Chord name overlay

### `audio.test-data`
Synthetic test data generation:
- Sine wave FFT simulation
- Chord generation (C major, D minor, etc.)
- Test without microphone

## Usage

### Browser Requirements

**HTTPS required** - Modern browsers require secure context for microphone access.

**User gesture required** - Must call `audio/request-permission!` from button click or user event.

### Basic Setup

```clojure
(require '[audio.core :as audio]
         '[audio.pitch :as pitch])

;; Initialize audio (can be done early)
(audio/init-audio!)

;; Request mic access (must be from user gesture)
(-> (audio/request-permission!)
    (.then (fn [result]
             (when (= result :granted)
               (start-detection!)))))

;; Detection loop
(defn detect-notes []
  (when-let [bin-data (audio/get-current-bin-data)]
    (when-let [result (pitch/find-polyphonic bin-data :legit-energy 0.25)]
      (let [{:keys [legit-notes legit-midis]} result
            chord (pitch/detect-chord legit-notes)]
        (println "Notes:" legit-notes)
        (println "Chord:" chord)))))
```

### Test Mode (No Microphone)

```clojure
(require '[audio.test-data :as test-data])

;; Generate C major chord data
(def test-bin-data (test-data/make-test-data :c-major))

;; Run detection
(pitch/find-polyphonic test-bin-data :legit-energy 0.2)
;; => {:legit-notes [0 4 7], :legit-midis [60 64 67], ...}

(pitch/detect-chord [0 4 7])
;; => [0 "Major"]  ;; C Major
```

### Available Test Cases

- `:c-major` - C E G (60 64 67)
- `:d-minor` - D F A (62 65 69)
- `:g-dominant-7` - G B D F (67 71 74 77)
- `:single-note` - C (60)
- `:octave` - C C' (60 72)
- `:fifth` - C G (60 67)

## Improvements Over Original

### Architecture
- **Functional style** - Pure functions, immutable data
- **Better naming** - Clear intent (e.g., `cluster-neighbors` vs `clusterBinsByMidi`)
- **Threading macros** - Cleaner data pipelines (`->>`, `some->`)

### Features
- **OKLCH colors** - Better perceptual uniformity than HSL
- **Test mode** - Synthetic data for development/testing
- **Simplified API** - Easier to integrate and test

### Code Quality
- **Documented** - Docstrings on all public functions
- **Testable** - Pure functions, no hidden state
- **Type hints** - Where appropriate for performance

## Color System (OKLCH)

OKLCH is a perceptually uniform color space:

- **L** (lightness): 0-1, we use 0.65 for visibility
- **C** (chroma): 0-0.4, we use 0.15 for vibrancy
- **H** (hue): 0-360°, mapped from pitch class (C=60°, rotating counterclockwise)

Benefits over HSL:
- Perceptually uniform brightness
- More vibrant colors at same saturation
- Better accessibility

## Algorithm Notes

The pitch detection works in these steps:

1. **Energy filtering** - Keep bins above threshold
2. **Neighbor clustering** - Group adjacent frequency bins
3. **Peak selection** - Find highest energy in each cluster
4. **MIDI conversion** - Round to nearest MIDI note
5. **Range clipping** - Focus on 2-octave range
6. **Harmonic analysis** - Find most common pitch class count
7. **Octave filtering** - Remove duplicate octaves
8. **Chord matching** - Match intervals to known patterns

## Performance

- FFT size: 16384 (high resolution for low frequencies)
- Bin count: 500 (covers MIDI 0-88, piano range)
- Sample rate: 48kHz (browser default)
- Frame rate: 60fps (requestAnimationFrame)

## License

Based on [polyphon](https://github.com/best/polyphon) (original implementation).
