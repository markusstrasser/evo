(ns utils.fuzzy-search
  "Pure fuzzy string matching utilities.

   Provides simple, fast fuzzy search for autocomplete use cases.
   No external dependencies - just string operations."
  (:require [clojure.string :as str]))

(defn normalize
  "Normalize string for comparison: lowercase and trim."
  [s]
  (when s
    (-> s str/trim str/lower-case)))

(defn contains-chars?
  "Check if haystack contains all chars of needle in order.

   Returns true if fuzzy match, false otherwise.

   Example:
     (contains-chars? \"projects\" \"prj\") => true
     (contains-chars? \"projects\" \"xyz\") => false"
  [haystack needle]
  (let [h (normalize haystack)
        n (normalize needle)]
    (if (str/blank? n)
      true
      (loop [h-idx 0
             n-idx 0]
        (cond
          (>= n-idx (count n)) true
          (>= h-idx (count h)) false
          (= (nth h h-idx) (nth n n-idx)) (recur (inc h-idx) (inc n-idx))
          :else (recur (inc h-idx) n-idx))))))

(defn match-score
  "Calculate match quality score (higher is better).

   Scoring:
   - Exact match: 1000
   - Starts with query: 100 + length bonus
   - Contains query substring: 50 + position bonus
   - Fuzzy match (chars in order): 10 + density bonus
   - No match: 0

   Example:
     (match-score \"Projects\" \"pro\") => 100+ (prefix)
     (match-score \"My Projects\" \"pro\") => 50+ (contains)
     (match-score \"Programming\" \"prg\") => 10+ (fuzzy)"
  [haystack needle]
  (let [h (normalize haystack)
        n (normalize needle)]
    (cond
      (str/blank? n) 50 ; Empty query matches everything moderately

      (= h n)
      1000 ; Exact match

      (str/starts-with? h n)
      (+ 100 (- 50 (count h))) ; Prefix match, shorter strings rank higher

      (str/includes? h n)
      (let [idx (str/index-of h n)]
        (+ 50 (- 20 idx))) ; Substring match, earlier position ranks higher

      (contains-chars? h n)
      (let [density (/ (count n) (count h))]
        (+ 10 (* 10 density))) ; Fuzzy match, denser matches rank higher

      :else 0)))

(defn fuzzy-filter
  "Filter and sort items by fuzzy match against query.

   items: Sequence of items to filter
   query: Search string
   key-fn: Function to extract searchable string from item (default: identity)
   limit: Max results to return (default: 10)

   Returns items sorted by match score (best first), excluding non-matches.

   Example:
     (fuzzy-filter [\"Projects\" \"Tasks\" \"Programming\"] \"pro\")
     => [\"Projects\" \"Programming\"]

     (fuzzy-filter pages \"pro\" :title 5)
     => [{:title \"Projects\"} {:title \"My Project\"}]"
  ([items query]
   (fuzzy-filter items query identity 10))
  ([items query key-fn]
   (fuzzy-filter items query key-fn 10))
  ([items query key-fn limit]
   (if (str/blank? query)
     (take limit items)
     (->> items
          (map (fn [item]
                 {:item item
                  :score (match-score (key-fn item) query)}))
          (filter #(pos? (:score %)))
          (sort-by :score >)
          (take limit)
          (map :item)))))

(defn highlight-match
  "Find character positions that match the query for highlighting.

   Returns vector of [start end] ranges that should be highlighted.

   Example:
     (highlight-match \"Projects\" \"pro\")
     => [[0 3]]  ; highlight 'Pro'

     (highlight-match \"My Projects\" \"pro\")
     => [[3 6]]  ; highlight 'Pro' in middle"
  [haystack needle]
  (let [h (normalize haystack)
        n (normalize needle)]
    (cond
      (str/blank? n) []

      ;; Substring match - highlight the substring
      (str/includes? h n)
      (let [idx (str/index-of h n)]
        [[idx (+ idx (count n))]])

      ;; Fuzzy match - highlight individual characters
      (contains-chars? h n)
      (loop [h-idx 0
             n-idx 0
             ranges []]
        (cond
          (>= n-idx (count n)) ranges
          (>= h-idx (count h)) ranges
          (= (nth h h-idx) (nth n n-idx))
          (recur (inc h-idx) (inc n-idx) (conj ranges [h-idx (inc h-idx)]))
          :else (recur (inc h-idx) n-idx ranges)))

      :else [])))
