(ns audio.pitch
  "Polyphonic pitch detection algorithm.

  Ported from polyphon project with improvements:
  - Functional ClojureScript style
  - Better naming and documentation
  - Optimized clustering logic")

(defn cluster-neighbors
  "Group consecutive bins into clusters.
  Input: [1 2 5 6 15 16]
  Output: [[1 2] [5 6] [15 16]]"
  [bins]
  (when (seq bins)
    (->> bins
         (reduce
          (fn [clusters bin]
            (let [last-cluster (peek clusters)
                  last-bin (peek last-cluster)]
              (if (and last-bin (< (Math/abs (- bin last-bin)) 2))
                ;; Add to current cluster
                (conj (pop clusters) (conj last-cluster bin))
                ;; Start new cluster
                (conj clusters [bin]))))
          [[]])
         (remove empty?)
         vec)))

(defn find-cluster-winner
  "Find the bin with highest energy in a cluster"
  [cluster bin-data]
  (->> cluster
       (apply max-key #(get-in bin-data [% :energy-abs] 0))))

(defn clip-midis
  "Keep only MIDIs within [start, end] range"
  [midis start end]
  (filter #(and (>= % start) (<= % end)) midis))

(defn find-note-count-max
  "Find the maximum occurrence count across all 12 pitch classes"
  [midis]
  (->> (range 12)
       (map (fn [pitch-class]
              (count (filter #(= (mod % 12) pitch-class) midis))))
       (apply max 0)))

(defn group-midis-by-note
  "Group MIDIs by their pitch class (0-11).
  Returns vector of 12 vectors, one per pitch class."
  [midis]
  (vec
   (for [pitch-class (range 12)]
     (vec (filter #(= (mod % 12) pitch-class) midis)))))

(defn notes-with-count
  "Find pitch classes that appear exactly 'n' times"
  [midis-2d n]
  (if (zero? n)
    []
    (sort
     (keep-indexed
      (fn [_idx midi-group]
        (when (= (count midi-group) n)
          (mod (first midi-group) 12)))
      midis-2d))))

(defn find-polyphonic
  "Main polyphonic pitch detection algorithm.

  Input:
  - bin-data: vector of bin maps with :energy-rel, :energy-abs, :midi, :bin-nr
  - legit-energy: threshold for considering a bin (0-1, default 0.2)

  Output:
  - {:legit-notes [pitch-classes...]
     :legit-midis [midi-numbers...]
     :display-midis [midi-numbers...]}"
  [bin-data & {:keys [legit-energy] :or {legit-energy 0.2}}]

  ;; Step 1: Find bins with enough energy
  (let [top-bins (->> bin-data
                      (keep-indexed
                       (fn [idx bin]
                         (when (> (:energy-rel bin) legit-energy)
                           (:bin-nr bin))))
                      distinct
                      sort
                      vec)]

    (when (seq top-bins)
      ;; Step 2: Cluster neighboring bins
      (let [clustered (cluster-neighbors top-bins)

            ;; Step 3: Find winner in each cluster
            winner-bins (mapv #(find-cluster-winner % bin-data) clustered)

            ;; Step 4: Convert to rounded MIDIs
            top-midis-round (->> winner-bins
                                 (map #(js/Math.round (:midi (get bin-data %))))
                                 distinct
                                 sort
                                 vec)

            ;; Step 5: Clip to 2-octave range from lowest note
            start-midi (first top-midis-round)
            end-midi (+ start-midi 23) ;; 2 octaves
            clipped-midis (clip-midis top-midis-round start-midi end-midi)

            ;; Step 6: Find most common occurrence count
            max-count (find-note-count-max clipped-midis)
            midis-by-note (group-midis-by-note clipped-midis)

            ;; Step 7: Get pitch classes with max count (likely the chord)
            note-guesses (notes-with-count midis-by-note max-count)

            ;; Step 8: Filter out octave duplicates
            display-midis (filterv
                           (fn [midi]
                             (and (some #(= (mod midi 12) %) note-guesses)
                                  (not (or (some #(= % (- midi 12)) clipped-midis)
                                           (some #(= % (- midi 24)) clipped-midis)))))
                           clipped-midis)]

        {:legit-notes note-guesses
         :legit-midis display-midis
         :display-midis display-midis
         :top-bins top-bins
         :top-midis clipped-midis}))))

;; Chord detection
(def chord-structures
  "Common chord interval patterns [name interval1 interval2 ...]"
  [["Major" 4 7]
   ["Major 6th" 4 7 9]
   ["Dominant 7th" 4 7 10]
   ["Major 7th" 4 7 11]
   ["Minor" 3 7]
   ["Minor 6th" 3 7 9]
   ["Minor 7th" 3 7 10]
   ["Augmented" 4 8]
   ["Diminished 7th" 3 6 9]
   ["Suspended 4" 5 7]
   ["Suspended 2" 2 7]])

(def chord-abbreviations
  {"Major" ""
   "Major 6th" "maj6"
   "Dominant 7th" "7"
   "Major 7th" "maj7"
   "Minor" "m"
   "Minor 6th" "m6"
   "Minor 7th" "m7"
   "Augmented" "aug"
   "Diminished 7th" "dim7"
   "Suspended 4" "sus4"
   "Suspended 2" "sus2"})

(defn calc-intervals
  "Calculate intervals from root note"
  [notes]
  (when (> (count notes) 1)
    (mapv #(mod (- % (first notes)) 12) (rest notes))))

(defn match-chord-pattern
  "Try to match intervals against known chord structures"
  [intervals]
  (some
   (fn [[chord-name & pattern]]
     (when (= (vec pattern) (vec intervals))
       chord-name))
   chord-structures))

(defn detect-chord
  "Detect chord from pitch classes (0-11).
  Returns [root-note chord-type] or nil"
  [pitch-classes]
  (when (> (count pitch-classes) 1)
    (let [sorted (sort pitch-classes)]
      ;; Try each note as root
      (some
       (fn [rotation-count]
         (let [rotated (vec (concat (drop rotation-count sorted)
                                    (map #(+ % 12) (take rotation-count sorted))))
               intervals (calc-intervals rotated)
               chord-name (match-chord-pattern intervals)]
           (when chord-name
             [(mod (first rotated) 12) chord-name])))
       (range (count sorted))))))
