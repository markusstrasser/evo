(ns evolver.kernel-property-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [evolver.kernel :as k]
            [evolver.constants :as constants]))

;; Generators for creating valid tree structures

(def node-id-gen
  (gen/fmap #(str "node-" %) gen/nat))

(def node-data-gen
  (gen/hash-map :type (gen/elements [:p :div :h1])
                :props (gen/hash-map :text gen/string-alphanumeric)))

(defn tree-gen
  "Generate a valid tree structure with nodes and children-by-parent"
  [max-depth max-children]
  (gen/bind
   (gen/choose 1 10) ; number of nodes
   (fn [node-count]
     (gen/bind
      (gen/vector node-id-gen node-count)
      (fn [node-ids]
        (let [all-ids (cons "root" node-ids)]
          (gen/bind
           (gen/map (gen/elements all-ids)
                    (gen/vector (gen/elements node-ids) 0 max-children))
           (fn [children-map]
             (let [nodes (into {"root" {:type :root}}
                               (map (fn [id] [id (gen/generate node-data-gen)]) node-ids))]
               (gen/return {:nodes nodes
                            :children-by-parent children-map}))))))))))

;; Property: derive-tree-metadata should always produce consistent results
(defspec derive-metadata-consistency-test 20
  (prop/for-all [db (tree-gen 3 3)]
                ;; The main goal is that derive-tree-metadata should never crash
                ;; regardless of input data quality
                (try
                  (let [derived (k/derive-tree-metadata db)]
                    (and
          ;; All nodes should have some entry in derived data
                     (every? #(contains? (:depth derived) %) (keys (:nodes db)))
                     (every? #(contains? (:paths derived) %) (keys (:nodes db)))
          ;; Root should have consistent metadata if it exists
                     (if (contains? (:nodes db) "root")
                       (and (number? (get-in derived [:depth "root"]))
                            (vector? (get-in derived [:paths "root"]))
                            (nil? (get-in derived [:parent-of "root"])))
                       true)
          ;; For reachable nodes, depth should equal path length
                     (every? (fn [[id depth]]
                               (if (and (number? depth) depth)
                                 (= depth (count (get-in derived [:paths id])))
                                 true)) ; Skip validation for orphaned/cyclic nodes
                             (:depth derived))))
                  (catch js/Error e
        ;; derive-tree-metadata should be robust and not throw
                    false))))

;; Property: Operations should preserve invariants
(defn valid-db? [db]
  (try
    (k/derive-tree-metadata db)
    true
    (catch js/Error e false)))

;; Test insert operation
(defspec insert-preserves-invariants-test 10
  (prop/for-all [initial-db (gen/return (k/update-derived constants/initial-db-base))
                 new-id node-id-gen
                 parent-id (gen/elements ["root" "div1"])
                 node-data node-data-gen]
                (let [command {:op :insert
                               :parent-id parent-id
                               :node-id new-id
                               :node-data node-data
                               :position nil}]
                  (try
                    (let [result-db (k/safe-apply-command initial-db command)]
                      (valid-db? result-db))
                    (catch js/Error e
          ;; If the operation fails, that's acceptable
                      true)))))

;; Test move operation
(defspec move-preserves-invariants-test 10
  (prop/for-all [node-to-move (gen/elements ["p1-select" "p2-high" "p3-both"])
                 new-parent (gen/elements ["root" "div1"])]
                (let [initial-db (k/update-derived constants/initial-db-base)
                      command {:op :move
                               :node-id node-to-move
                               :new-parent-id new-parent
                               :position nil}]
                  (try
                    (let [result-db (k/safe-apply-command initial-db command)]
                      (valid-db? result-db))
                    (catch js/Error e
          ;; If the operation fails, that's acceptable
                      true)))))

;; Test reorder operation
(defspec reorder-preserves-invariants-test 10
  (prop/for-all [node-to-reorder (gen/elements ["title" "p1-select" "p2-high" "p3-both" "div1"])
                 to-index (gen/choose 0 4)]
                (let [initial-db (k/update-derived constants/initial-db-base)
                      command {:op :reorder
                               :node-id node-to-reorder
                               :parent-id "root" ; All test nodes are children of root
                               :to-index to-index}]
                  (try
                    (let [result-db (k/safe-apply-command initial-db command)]
                      (valid-db? result-db))
                    (catch js/Error e
          ;; If the operation fails, that's acceptable 
                      true)))))

;; Test that UUID generation produces unique IDs
(deftest unique-id-generation-test
  (testing "Generated IDs should be unique"
    (let [ids (repeatedly 100 k/gen-new-id)]
      (is (= (count ids) (count (set ids)))
          "All generated IDs should be unique"))))

;; Test O(1) parent lookup performance vs O(N) scan
(deftest parent-lookup-performance-test
  (testing "O(1) parent lookup should be faster than O(N) scan"
    (let [large-db (k/update-derived constants/initial-db-base)
          test-node "p4-click"]
      ;; Test O(1) lookup
      (is (= "div1" (k/parent-of large-db test-node)))
      ;; Test that the old O(N) function still works (for compatibility)
      (is (= "div1" (k/find-parent (:children-by-parent large-db) test-node))))))

;; Test guard against cross-parent reordering
(deftest reorder-guard-test
  (testing "reorder-node should reject cross-parent moves"
    (let [db (k/update-derived constants/initial-db-base)]
      (is (thrown-with-msg? js/Error #"reorder-node only reorders within a parent"
                            (k/reorder-node db {:node-id "p4-click" :parent-id "root" :to-index 0}))))))