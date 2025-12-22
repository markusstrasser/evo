(ns utils.collection
  "Generic collection utilities for sets, vectors, and navigation.

   Design principles:
   - Pure functions with no side effects
   - Domain-agnostic (not tied to specific business logic)
   - Efficient implementations using reduce over apply
   - Clear docstrings with examples")

;; ── Set Operations ────────────────────────────────────────────────────────────

(defn toggle-membership
  "Toggle membership of item in set. Returns new set.

   Examples:
     (toggle-membership #{:a :b} :c)    ;=> #{:a :b :c}
     (toggle-membership #{:a :b} :b)    ;=> #{:a}
     (toggle-membership #{} :x)         ;=> #{:x}"
  [s item]
  (if (contains? s item)
    (disj s item)
    (conj s item)))

(defn remove-all
  "Remove all items from set. More efficient than (apply disj ...).

   Examples:
     (remove-all #{:a :b :c} [:b :c])   ;=> #{:a}
     (remove-all #{:a :b} [])           ;=> #{:a :b}
     (remove-all #{} [:x])              ;=> #{}"
  [s items]
  (reduce disj s items))

(defn add-all
  "Add all items to set. More efficient than (apply conj ...).

   Examples:
     (add-all #{:a} [:b :c])            ;=> #{:a :b :c}
     (add-all #{} [:x :y])              ;=> #{:x :y}
     (add-all #{:a} [])                 ;=> #{:a}"
  [s items]
  (reduce conj s items))

;; ── Vector Operations ─────────────────────────────────────────────────────────

(defn cap-vector
  "Cap vector at max-size by dropping oldest entries (from start).
   Uses subvec when needed for efficiency.

   Examples:
     (cap-vector [1 2 3 4 5] 3)         ;=> [3 4 5]
     (cap-vector [1 2] 5)               ;=> [1 2]
     (cap-vector [] 5)                  ;=> []"
  [v max-size]
  (let [size (count v)]
    (if (> size max-size)
      (subvec v (- size max-size))
      v)))

;; ── Navigation Helpers ────────────────────────────────────────────────────────

(defn navigate-index
  "Calculate new index after navigation in given direction.
   Bounds result to [0, max-idx].

   direction: :up (decrement) or :down (increment)
   current-idx: Current position (0-indexed)
   max-idx: Maximum valid index (inclusive)

   Examples:
     (navigate-index :up 5 10)          ;=> 4
     (navigate-index :down 5 10)        ;=> 6
     (navigate-index :up 0 10)          ;=> 0  (can't go below 0)
     (navigate-index :down 9 10)        ;=> 9  (can't exceed max-idx)
     (navigate-index :down 10 10)       ;=> 10 (already at max)"
  [direction current-idx max-idx]
  (let [delta (case direction :up -1 :down 1 0)
        new-idx (+ current-idx delta)]
    (max 0 (min new-idx max-idx))))
