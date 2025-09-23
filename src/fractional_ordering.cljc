(ns fractional-ordering
  (:require [datascript.core :as d]))
;; ## 2. ORDERING LOGIC ##
;; Simplified implementation of fractional indexing.
(defn- get-mid-string
  "Calculates a string that lexicographically sorts between two others."
  [prev next]
  (let [p (or prev "")
        n (or next "z")]
    (cond
      ;; When prev is empty and next exists, return something that comes before next
      (and (empty? p) next)
      (str (char (max (int \a) (dec (int (first n))))))

      ;; When next is empty/nil and prev exists, return something that comes after prev  
      (and (nil? next) (not (empty? p)))
      (str p "m")

      ;; Both are empty - return middle
      (and (empty? p) (nil? next))
      "m"

      ;; Both exist - find middle
      :else
      (let [len (min (count p) (count n))]
        (if-let [diff-idx (first (filter #(not= (get p %) (get n %)) (range len)))]
          ;; Characters differ at diff-idx
          (let [prefix (subs p 0 diff-idx)
                p-char (get p diff-idx)
                n-char (get n diff-idx)
                mid-ord (quot (+ (int p-char) (int n-char)) 2)]
            (if (> mid-ord (int p-char))
              (str prefix (char mid-ord))
              (str p (char (quot (+ (int (get p (inc diff-idx) \a)) (int \z)) 2)))))
          ;; No character differences in common prefix
          (cond
            ;; p is shorter than n (e.g. "m" vs "mm")
            (< (count p) (count n))
            (str p (char (quot (+ (int \a) (int (get n (count p)))) 2)))

            ;; n is shorter than p 
            (> (count p) (count n))
            (str (subs p 0 (count n)) (char (quot (+ (int (get p (count n))) (int \z)) 2)))

            ;; Same length, same content - should not happen in practice
            :else
            (str p "m")))))))

(defn- get-ordered-siblings [db parent-ref]
  (->> (d/q '[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
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
                   next-order (when (< (inc target-idx) (count siblings))
                                (nth siblings (inc target-idx)))]
               (get-mid-string target-order next-order))
      :before (let [target-order (:order (d/entity db [:id target]))
                    target-idx (.indexOf siblings target-order)
                    prev-order (when (pos? target-idx) (nth siblings (dec target-idx)))]
                (get-mid-string prev-order target-order)))))
