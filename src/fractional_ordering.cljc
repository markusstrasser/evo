(ns fractional-ordering
  (:require [datascript.core :as d]))
;; ## 2. ORDERING LOGIC ##
;; Simplified implementation of fractional indexing.
(defn- get-mid-string
  "Calculates a string that lexicographically sorts between two others."
  [prev next]
  (let [p (or prev "")
        n (or next "z")
        len (min (count p) (count n))]
    (if-let [diff-idx (first (filter #(not= (get p %) (get n %)) (range len)))]
      (let [prefix (subs p 0 diff-idx)
            p-char (get p diff-idx)
            n-char (get n diff-idx)
            mid-ord (quot (+ (int p-char) (int n-char)) 2)]
        (if (> mid-ord (int p-char))
          (str prefix (char mid-ord))
          (str p (char (quot (+ (int (get p (inc diff-idx) \a)) (int \z)) 2)))))
      (str p "m"))))

(defn- get-ordered-siblings [db parent-ref]
  (->> (d/q '[:find ?o :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db parent-ref)
       (mapv first)
       (remove nil?)
       (sort-by str)))

(defn calculate-order [db parent-ref {:keys [rel target]}]
  (let [siblings (get-ordered-siblings db parent-ref)]
    (case rel
      :first (get-mid-string nil (first siblings))
      :last (get-mid-string (last siblings) nil)
      :after (let [target-order (:order (d/entity db [:id target]))
                   target-idx (.indexOf siblings target-order)
                   next-order (get siblings (inc target-idx))]
               (get-mid-string target-order next-order))
      :before (let [target-order (:order (d/entity db [:id target]))
                    target-idx (.indexOf siblings target-order)
                    prev-order (when (pos? target-idx) (get siblings (dec target-idx)))]
                (get-mid-string prev-order target-order)))))
