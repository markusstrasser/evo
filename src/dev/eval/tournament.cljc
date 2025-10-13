(ns dev.eval.tournament
  "Swiss-Lite tournament scheduling with SWIM variant.
   Concentrates comparisons where BT uncertainty is highest."
  (:require [clojure.set :as set]))

(defn seed-round
  "Generate initial random pairings to reach minimum degree.
   Ensures all items have at least min-degree comparisons."
  [items {:keys [min-degree]}]
  (let [item-ids (map :id items)
        n (count item-ids)
        target-edges (* n min-degree)
        shuffled (shuffle (for [i item-ids
                                j item-ids
                                :when (< (compare i j) 0)]
                            [i j]))]
    (take (/ target-edges 2) shuffled)))

(defn brackets
  "Split items into k brackets based on BT scores (theta).
   Returns vector of item-id sets, ordered by strength."
  [theta items k]
  (let [sorted-ids (sort-by #(get theta % 0.0) #(compare %2 %1) (map :id items))
        n (count sorted-ids)
        bracket-size (max 1 (quot n k))]
    (->> sorted-ids
         (partition-all bracket-size)
         (mapv set))))

(defn swiss-pairs
  "Generate within-bracket pairings (Swiss-Lite).
   Pairs items with similar BT scores to reduce variance."
  [theta items bracket]
  (let [sorted-ids (sort-by #(get theta % 0.0) #(compare %2 %1) bracket)
        pairs (partition 2 2 nil sorted-ids)]
    (keep (fn [[i j]]
            (when (and i j)
              (if (< (compare i j) 0) [i j] [j i])))
          pairs)))

(defn swim-pairs
  "SWIM variant: pair strongest with weakest within bracket.
   Creates more informative comparisons for uncertain items."
  [theta items bracket]
  (let [sorted-ids (sort-by #(get theta % 0.0) #(compare %2 %1) bracket)
        n (count sorted-ids)
        half (quot n 2)
        strong (take half sorted-ids)
        weak (reverse (drop half sorted-ids))]
    (keep (fn [[s w]]
            (when (and s w)
              (if (< (compare s w) 0) [s w] [w s])))
          (map vector strong weak))))

(defn schedule-round
  "Schedule next round of comparisons based on current state.
   Returns list of [id-left id-right] pairs."
  [state {:keys [style brackets-k min-degree]}]
  (let [{:keys [theta items degree-map existing-edges]} state
        item-ids (map :id items)
        ;; Use seed round if no comparisons yet
        needs-seed? (empty? existing-edges)]
    (if needs-seed?
      (seed-round items {:min-degree (or min-degree 3)})
      (let [bracket-sets (brackets theta items (or brackets-k 8))
            pair-fn (case style
                      :swiss swiss-pairs
                      :swim swim-pairs
                      swiss-pairs)]
        (->> bracket-sets
             (mapcat #(pair-fn theta items %))
             (remove (fn [edge]
                       (or (contains? existing-edges edge)
                           (contains? existing-edges (reverse edge)))))
             (take 20))))))  ; Limit round size

(defn update-state
  "Update tournament state after round completion.
   Tracks degree per item and existing edges."
  [state new-edges]
  (let [degree-map (:degree-map state {})
        existing-edges (:existing-edges state #{})]
    (-> state
        (assoc :degree-map
               (reduce (fn [acc [l r]]
                         (-> acc
                             (update l (fnil inc 0))
                             (update r (fnil inc 0))))
                       degree-map
                       new-edges))
        (assoc :existing-edges
               (into existing-edges (concat new-edges
                                            (map reverse new-edges)))))))
