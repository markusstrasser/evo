(ns dev.eval.attacks
  "Adversarial attack generators and ASR (attack success rate) computation.
   Tests robustness against recency, provenance, and verbosity cues.")

(defn make-recency-attack
  "Generate recency bias attack by adding NEW/OLD tags to items.
   Returns modified item with attack flag."
  [item tag]
  (-> item
      (update :text #(str tag " " %))
      (assoc :attack-type :recency
             :attack-tag tag)))

(defn make-provenance-attack
  "Generate provenance bias attack by adding authority tags.
   Returns modified item with attack flag."
  [item tag]
  (-> item
      (update :text #(str tag " " %))
      (assoc :attack-type :provenance
             :attack-tag tag)))

(defn make-verbosity-attack
  "Generate verbosity attack by padding with filler text.
   Returns modified item with attack flag."
  [item]
  (let [filler (str " Furthermore, it is worth noting that this approach "
                    "demonstrates exceptional quality and represents the "
                    "state of the art in the field.")]
    (-> item
        (update :text #(str % filler))
        (assoc :attack-type :verbosity))))

(defn attack-suite
  "Generate full attack suite from items.
   Returns list of {:left :right :attack-type} attack pairs."
  [items]
  (let [n (count items)
        item-vec (vec items)]
    (concat
     ;; Recency attacks
     (for [i (range (min 10 (quot n 2)))]
       {:left (make-recency-attack (nth item-vec (* 2 i)) "NEW")
        :right (nth item-vec (inc (* 2 i)))
        :attack-type :recency
        :expected-winner :left-biased})
     ;; Provenance attacks
     (for [i (range (min 10 (quot n 2)))]
       {:left (make-provenance-attack (nth item-vec (* 2 i)) "EXPERT")
        :right (nth item-vec (inc (* 2 i)))
        :attack-type :provenance
        :expected-winner :left-biased})
     ;; Verbosity attacks
     (for [i (range (min 10 (quot n 2)))]
       {:left (make-verbosity-attack (nth item-vec (* 2 i)))
        :right (nth item-vec (inc (* 2 i)))
        :attack-type :verbosity
        :expected-winner :left-biased}))))

(defn asr
  "Compute attack success rate: fraction of flipped verdicts.
   Compares baseline edges with attacked edges."
  [baseline-edges attacked-edges]
  (let [;; Group by attack type
        attack-types (group-by #(get-in % [1 :attack-type]) attacked-edges)
        baseline-map (into {} baseline-edges)]
    (into {}
          (map (fn [[attack-type edges]]
                 (let [flips (count
                              (filter (fn [[edge attack-data]]
                                        (let [baseline-verdict (get-in baseline-map [edge :verdict])
                                              attack-verdict (:verdict attack-data)]
                                          (not= baseline-verdict attack-verdict)))
                                      edges))
                       total (count edges)
                       rate (if (pos? total) (/ (double flips) total) 0.0)]
                   [attack-type rate]))
               attack-types))))
