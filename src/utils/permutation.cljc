(ns utils.permutation
  "Pure permutation algebra for sibling reordering.

  Permutations are represented as maps {index → index} with fixed points elided.
  This provides invertibility, composition, and law-checkable behavior.

  Inspired by Electric/Hyperfiddle's incseq/permutation, but independent implementation.

  ARCHIVED: This module is currently unused. Preserved in utils for potential future use.
  The active implementation in plugins.permute uses direct vector calculations instead.")

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Data & Constants
;; ══════════════════════════════════════════════════════════════════════════════

(def identity-perm
  "Identity permutation. All elements stay in place."
  {})

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn compose
  "Compose two permutations: (compose p q) means apply q first, then p.
   This implements function composition: (p ∘ q)(x) = p(q(x))

   Laws:
   - Associative: (compose p (compose q r)) = (compose (compose p q) r)
   - Identity: (compose p identity-perm) = (compose identity-perm p) = p"
  [p q]
  (let [;; Collect all indices that are moved by either permutation
        all-indices (into (set (keys p)) (keys q))
        ;; For each index, trace through both permutations
        result (reduce (fn [m i]
                         (let [after-q (get q i i)  ; q moves i to...
                               after-p (get p after-q after-q)]  ; then p moves that to...
                           (if (= i after-p)
                             m  ; Fixed point, elide
                             (assoc m i after-p))))
                       {}
                       all-indices)]
    result))

(defn inverse
  "Compute inverse permutation: p⁻¹

   Law: (compose p (inverse p)) = identity-perm"
  [p]
  (reduce-kv (fn [m k v]
               (assoc m v k))
             {}
             p))

(defn arrange
  "Apply permutation p to vector v.
   Returns a new vector with elements reordered according to p.

   Semantics: p[i] = j means 'element at position i goes to position j'

   Example: (arrange [:a :b :c] {0 2, 1 0, 2 1}) => [:b :c :a]
            Position 0 goes to 2, so :a ends up at 2
            Position 1 goes to 0, so :b ends up at 0
            Position 2 goes to 1, so :c ends up at 1
            Result: [:b :c :a]"
  [v p]
  (let [n (count v)
        ;; Compute inverse: p[src] = dst, so inv[dst] = src
        inv (inverse p)]
    (persistent!
     (reduce (fn [result dst]
               (let [;; Get source from inverse, or dst if not in map (fixed point)
                     src (get inv dst dst)]
                 (assoc! result dst (nth v src))))
             (transient (vec v))
             (range n)))))

(defn rotation
  "Create permutation that moves element at index `from` to index `to`.
   Elements between from and to shift to fill the gap.

   Examples:
   - (rotation 0 2) on [a b c d] => [b c a d]
   - (rotation 2 0) on [a b c d] => [c a b d]"
  [from to]
  (cond
    (= from to) identity-perm

    (< from to)
    ;; Moving right: [from] goes to [to], [from+1..to] shift left
    (into {from to}
          (map (fn [i] [i (dec i)]))
          (range (inc from) (inc to)))

    :else  ; (> from to)
    ;; Moving left: [from] goes to [to], [to..from-1] shift right
    (into {from to}
          (map (fn [i] [i (inc i)]))
          (range to from))))

(defn split-swap
  "Swap two contiguous blocks starting at index i.
   First block has length l, second block has length r.

   Example: (split-swap 1 2 2) on [a b c d e] => [a d e b c]
            Swaps [b c] with [d e]

   Returns identity-perm if either block is empty."
  [i l r]
  (if (and (pos? l) (pos? r))
    (merge
     ;; First block [i..i+l-1] moves right by r positions
     (into {} (map (fn [idx] [idx (+ idx r)]))
           (range i (+ i l)))
     ;; Second block [i+l..i+l+r-1] moves left by l positions
     (into {} (map (fn [idx] [idx (- idx l)]))
           (range (+ i l) (+ i l r))))
    identity-perm))

(defn from-to
  "Compute permutation that transforms src vector into dst vector.
   src and dst must contain the same elements (same multiset).

   Example: (from-to [:a :b :c] [:b :c :a]) => {0 2, 1 0, 2 1}

   Throws if src and dst don't contain the same elements."
  [src dst]
  (assert (= (set src) (set dst))
          "from-to: src and dst must contain the same elements")
  (assert (= (count src) (count dst))
          "from-to: src and dst must have the same length")

  (let [n (count src)
        ;; Build index map: element -> queue of indices in dst where it appears
        dst-indices (reduce (fn [m idx]
                              (update m (nth dst idx) (fnil conj []) idx))
                            {}
                            (range n))]
    ;; For each position in src, consume one dst index for that element
    (-> (reduce (fn [[perm remaining] src-idx]
                  (let [elem (nth src src-idx)
                        [dst-idx & rest-indices] (get remaining elem)
                        remaining' (if (seq rest-indices)
                                     (assoc remaining elem rest-indices)
                                     (dissoc remaining elem))
                        perm' (if (= src-idx dst-idx)
                                perm  ; Fixed point, elide
                                (assoc perm src-idx dst-idx))]
                    [perm' remaining']))
                [{} dst-indices]
                (range n))
        first)))

(defn- gcd
  "Greatest common divisor using Euclidean algorithm."
  [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn- lcm
  "Least common multiple of two integers."
  [a b]
  (quot (* a b) (gcd a b)))

(defn- trace-cycle
  "Find cycle length starting from index i in permutation p.
   Returns [cycle-length visited-set]."
  [p start visited]
  (loop [curr start
         len 0
         vis visited]
    (let [vis' (conj vis curr)
          nxt (get p curr curr)
          len' (inc len)]
      (if (= nxt start)
        [len' vis']
        (recur nxt len' vis')))))

(defn- find-cycle-lengths
  "Extract all cycle lengths from permutation p."
  [p]
  (loop [unvisited (keys p)
         visited #{}
         lengths []]
    (if-let [start (first unvisited)]
      (if (contains? visited start)
        (recur (rest unvisited) visited lengths)
        (let [[cycle-len visited'] (trace-cycle p start visited)]
          (recur (rest unvisited) visited' (conj lengths cycle-len))))
      lengths)))

(defn order
  "Compute the order of permutation p (minimal n where p^n = identity-perm).
   This is the least common multiple of all cycle lengths.

   Returns 1 for the identity permutation."
  [p]
  (if (empty? p)
    1
    (->> p
         find-cycle-lengths
         (reduce lcm 1))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn normalize
  "Ensure permutation is in canonical form (fixed points elided)."
  [p]
  (reduce-kv (fn [m k v]
               (if (= k v)
                 m
                 (assoc m k v)))
             {}
             p))
