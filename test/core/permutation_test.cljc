(ns core.permutation-test
  "Property-based tests for permutation algebra.
   Validates group laws, arrange correctness, and operation properties."
(:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [utils.permutation-math :as perm]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Generators
;; ══════════════════════════════════════════════════════════════════════════════

(defn gen-permutation
  "Generate a random permutation for size n.
   Returns a map {index → index} representation."
  [n]
  (gen/fmap (fn [shuffled-indices]
              ;; Convert shuffled vector to permutation map
              (reduce (fn [m [src dst]]
                        (if (= src dst)
                          m  ; Elide fixed points
                          (assoc m src dst)))
                      {}
                      (map vector (range n) shuffled-indices)))
            (gen/shuffle (range n))))

(defn gen-distinct-vec
  "Generate a vector of n distinct keywords for easy equality checks."
  [n]
  (gen/vector (gen/fmap (fn [i] (keyword (str "k" i))) gen/nat) n))

(def gen-small-permutation
  "Generator for small permutations (size 3-7) for faster testing."
  (gen/bind (gen/choose 3 7)
            gen-permutation))

(def gen-small-vec
  "Generator for small vectors with distinct elements."
  (gen/bind (gen/choose 3 7)
            (fn [n]
              (gen/fmap (fn [indices]
                          (mapv (fn [i] (keyword (str "k" i))) indices))
                        (gen/vector gen/nat n)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Group Law Properties
;; ══════════════════════════════════════════════════════════════════════════════

(defspec compose-associativity 100
  (prop/for-all [p gen-small-permutation
                 q gen-small-permutation
                 r gen-small-permutation]
    (= (perm/compose p (perm/compose q r))
       (perm/compose (perm/compose p q) r))))

(defspec compose-identity-left 100
  (prop/for-all [p gen-small-permutation]
    (= (perm/compose perm/identity-perm p) p)))

(defspec compose-identity-right 100
  (prop/for-all [p gen-small-permutation]
    (= (perm/compose p perm/identity-perm) p)))

(defspec compose-inverse 100
  (prop/for-all [p gen-small-permutation]
    (= (perm/compose p (perm/inverse p))
       perm/identity-perm)))

(defspec inverse-involutive 100
  (prop/for-all [p gen-small-permutation]
    (= (perm/inverse (perm/inverse p)) p)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Arrange Properties
;; ══════════════════════════════════════════════════════════════════════════════

(def gen-vec-and-perm
  "Generate a vector and a compatible permutation."
  (gen/bind (gen/choose 3 7)
            (fn [n]
              (gen/tuple
               (gen/fmap (fn [indices]
                           (mapv (fn [i] (keyword (str "k" i))) indices))
                         (gen/vector gen/nat n))
               (gen-permutation n)))))

(defspec arrange-roundtrip 100
  (prop/for-all [[v p] gen-vec-and-perm]
    (= (perm/arrange (perm/arrange v p) (perm/inverse p))
       v)))

(defspec arrange-identity 100
  (prop/for-all [v gen-small-vec]
    (= (perm/arrange v perm/identity-perm) v)))

(def gen-vec-and-two-perms
  "Generate a vector and two compatible permutations."
  (gen/bind (gen/choose 3 7)
            (fn [n]
              (gen/tuple
               (gen/fmap (fn [indices]
                           (mapv (fn [i] (keyword (str "k" i))) indices))
                         (gen/vector gen/nat n))
               (gen-permutation n)
               (gen-permutation n)))))

(defspec arrange-composition 100
  (prop/for-all [[v p q] gen-vec-and-two-perms]
    (= (perm/arrange v (perm/compose p q))
       (perm/arrange (perm/arrange v q) p))))

;; ══════════════════════════════════════════════════════════════════════════════
;; From-To Properties
;; ══════════════════════════════════════════════════════════════════════════════

(def gen-src-and-dst
  "Generate a vector and a shuffled version of it."
  (gen/bind gen-small-vec
            (fn [src]
              (gen/fmap (fn [dst] [src dst])
                        (gen/shuffle src)))))

(defspec from-to-correctness 100
  (prop/for-all [[src dst] gen-src-and-dst]
    (let [p (perm/from-to src dst)]
      (= (perm/arrange src p) dst))))

(defspec from-to-identity 50
  (prop/for-all [v gen-small-vec]
    (= (perm/from-to v v) perm/identity-perm)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Rotation Properties
;; ══════════════════════════════════════════════════════════════════════════════

(defspec rotation-no-op 50
  (prop/for-all [i (gen/choose 0 5)]
    (= (perm/rotation i i) perm/identity-perm)))

(defspec rotation-inverse 50
  (prop/for-all [i (gen/choose 0 5)
                 j (gen/choose 0 5)]
    (if (= i j)
      true  ; identity composed with itself is identity
      (= (perm/compose (perm/rotation i j) (perm/rotation j i))
         perm/identity-perm))))

(def gen-vec-with-rotation
  "Generate a vector with rotation indices."
  (gen/bind (gen/choose 3 7)
            (fn [n]
              (gen/tuple
               (gen/fmap (fn [indices]
                           (mapv (fn [i] (keyword (str "k" i))) indices))
                         (gen/vector gen/nat n))
               (gen/choose 0 (dec n))
               (gen/choose 0 (dec n))))))

(defspec rotation-correctness 100
  (prop/for-all [[v i j] gen-vec-with-rotation]
    (let [result (perm/arrange v (perm/rotation i j))
          elem (nth v i)]
      (= (nth result j) elem))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Split-Swap Properties
;; ══════════════════════════════════════════════════════════════════════════════

(defspec split-swap-self-inverse 50
  (prop/for-all [i (gen/choose 0 3)
                 len (gen/choose 1 3)]
    ;; Split-swap is only self-inverse when both blocks have the same length
    (let [swap (perm/split-swap i len len)]
      (= (perm/compose swap swap) perm/identity-perm))))

(defspec split-swap-correctness 100
  (prop/for-all [v (gen/vector gen/nat 10)
                 i (gen/choose 0 5)
                 l (gen/choose 1 3)
                 r (gen/choose 1 3)]
    (if (<= (+ i l r) (count v))
      (let [swap (perm/split-swap i l r)
            result (perm/arrange v swap)
            block-a (subvec v i (+ i l))
            block-b (subvec v (+ i l) (+ i l r))]
        ;; After swap, block-b should be at position i, block-a at i+r
        (and (= (subvec result i (+ i r)) block-b)
             (= (subvec result (+ i r) (+ i l r)) block-a)))
      true)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Order Properties
;; ══════════════════════════════════════════════════════════════════════════════

(defspec order-identity 10
  (prop/for-all [_unused gen/nat]
    (= (perm/order perm/identity-perm) 1)))

(defspec order-correctness 50
  (prop/for-all [p gen-small-permutation]
    (let [n (perm/order p)]
      ;; p^n should equal identity
      (= (reduce (fn [acc _] (perm/compose acc p))
                 perm/identity-perm
                 (range n))
         perm/identity-perm))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Unit Tests (specific cases)
;; ══════════════════════════════════════════════════════════════════════════════

(deftest rotation-examples
  (testing "rotation 0 to 2 on [a b c]"
    (is (= (perm/arrange [:a :b :c] (perm/rotation 0 2))
           [:b :c :a])))

  (testing "rotation 2 to 0 on [a b c]"
    (is (= (perm/arrange [:a :b :c] (perm/rotation 2 0))
           [:c :a :b]))))

(deftest split-swap-examples
  (testing "swap [b c] with [d e] in [a b c d e]"
    (let [swap (perm/split-swap 1 2 2)]
      (is (= (perm/arrange [:a :b :c :d :e] swap)
             [:a :d :e :b :c])))))

(deftest from-to-examples
  (testing "from [:a :b :c] to [:b :c :a]"
    (let [p (perm/from-to [:a :b :c] [:b :c :a])]
      (is (= p {0 2, 1 0, 2 1}))))

  (testing "from [:a :b :c] to [:c :b :a]"
    (let [p (perm/from-to [:a :b :c] [:c :b :a])]
      (is (= p {0 2, 2 0})))))

(deftest compose-examples
  (testing "compose two 3-cycles"
    (let [p {0 1, 1 2, 2 0}
          q {0 2, 1 0, 2 1}
          result (perm/compose p q)]
      ;; p∘q: apply q first (0→2, 1→0, 2→1), then p (0→1, 1→2, 2→0)
      ;; So: 0 → (q) 2 → (p) 0, fixed point
      ;;     1 → (q) 0 → (p) 1, fixed point
      ;;     2 → (q) 1 → (p) 2, fixed point
      (is (= result perm/identity-perm)))))

(deftest order-examples
  (testing "order of 3-cycle"
    (is (= (perm/order {0 1, 1 2, 2 0}) 3)))

  (testing "order of transposition"
    (is (= (perm/order {0 1, 1 0}) 2)))

  (testing "order of two disjoint transpositions"
    (is (= (perm/order {0 1, 1 0, 2 3, 3 2}) 2))))
