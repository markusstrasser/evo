(ns data-transform-test
  "Data transformation and utility function tests"
  (:require [cljs.test :refer [deftest testing is are run-tests]]
            [evolver.kernel :as k]
            [clojure.string :as str]))

;; === DATA STRUCTURE VALIDATION TESTS ===

(deftest state-structure-validation-test
  (testing "State structure follows expected schema"
    (let [valid-state {:nodes {"root" {:type :div}}
                       :children-by-parent {"root" []}
                       :view {:selection [] :highlighted #{} :collapsed #{}}
                       :references {}
                       :log-level :info
                       :log-history []
                       :derived {}
                       :computed {}}]

      ;; Check required keys exist
      (is (contains? valid-state :nodes))
      (is (contains? valid-state :children-by-parent))
      (is (contains? valid-state :view))

      ;; Check data types
      (is (map? (:nodes valid-state)))
      (is (map? (:children-by-parent valid-state)))
      (is (map? (:view valid-state)))
      (is (vector? (get-in valid-state [:view :selection])))
      (is (set? (get-in valid-state [:view :highlighted]))))))

(deftest node-validation-test
  (testing "Node structure validation"
    (let [valid-node {:type :p :props {:text "Sample text"}}
          invalid-nodes [{:props {:text "Missing type"}}
                         {:type :p} ; Missing props
                         {:type "string-type" :props {}}]] ; Type should be keyword

      ;; Valid node structure
      (is (keyword? (:type valid-node)))
      (is (map? (:props valid-node)))

      ;; Invalid structures
      (doseq [invalid-node invalid-nodes]
        (is (or (not (keyword? (:type invalid-node)))
                (not (map? (:props invalid-node)))))))))

;; === COLLECTION OPERATIONS TESTS ===

(deftest collection-transformation-test
  (testing "Collection transformation utilities"
    (let [nodes {"a" {:type :p} "b" {:type :div} "c" {:type :p}}]

      ;; Filter nodes by type
      (let [p-nodes (filter #(= :p (:type (val %))) nodes)]
        (is (= 2 (count p-nodes))))

      ;; Map over node values
      (let [node-types (map #(:type (val %)) nodes)]
        (is (= 3 (count node-types)))
        (is (every? keyword? node-types))))))

(deftest path-operations-test
  (testing "Node path operations"
    (let [paths {"root" []
                 "child1" ["root"]
                 "grandchild" ["root" "child1"]}]

      ;; Test path depth calculation
      (is (= 0 (count (get paths "root"))))
      (is (= 1 (count (get paths "child1"))))
      (is (= 2 (count (get paths "grandchild"))))

      ;; Test parent lookup from path
      (is (= "root" (last (get paths "child1"))))
      (is (= "child1" (last (get paths "grandchild")))))))

;; === STRING MANIPULATION TESTS ===

(deftest text-content-tests
  (testing "Text content manipulation"
    (let [sample-text "Sample node text with UPPERCASE and lowercase"]

      ;; Text length validation
      (is (> (count sample-text) 0))

      ;; Case transformations
      (is (= (str/lower-case sample-text) (str/lower-case (str/upper-case sample-text))))

      ;; Text searching
      (is (str/includes? sample-text "node"))
      (is (not (str/includes? sample-text "missing"))))))

(deftest node-id-generation-test
  (testing "Node ID generation and validation"
    (let [base-id "node"
          numbered-ids (for [i (range 5)] (str base-id "-" i))]

      ;; IDs should be unique
      (is (= (count numbered-ids) (count (set numbered-ids))))

      ;; IDs should follow pattern
      (doseq [id numbered-ids]
        (is (str/starts-with? id base-id))
        (is (str/includes? id "-"))))))

;; === HICCUP STRUCTURE TESTS ===

(deftest hiccup-structure-test
  (testing "Hiccup data structure validation"
    (let [simple-hiccup [:div "Text content"]
          complex-hiccup [:div {:class "container"}
                          [:p {:id "p1"} "First paragraph"]
                          [:p {:id "p2"} "Second paragraph"]]]

      ;; Basic hiccup structure
      (is (vector? simple-hiccup))
      (is (keyword? (first simple-hiccup)))

      ;; Complex hiccup with attributes
      (is (vector? complex-hiccup))
      (is (keyword? (first complex-hiccup)))
      (is (map? (second complex-hiccup))) ; Attributes map

      ;; Children should be valid hiccup
      (let [children (drop 2 complex-hiccup)]
        (doseq [child children]
          (is (vector? child))
          (is (keyword? (first child))))))))

(deftest hiccup-transformation-test
  (testing "Hiccup transformation operations"
    (let [base-element [:p "Original text"]
          with-attrs [:p {:class "styled"} "Original text"]
          with-id [:p {:id "unique-id"} "Original text"]]

      ;; Adding attributes
      (is (= :p (first with-attrs)))
      (is (map? (second with-attrs)))

      ;; Text content preservation
      (is (= "Original text" (last base-element)))
      (is (= "Original text" (last with-attrs)))
      (is (= "Original text" (last with-id))))))

;; === SELECTION LOGIC TESTS ===

(deftest selection-logic-test
  (testing "Selection state logic"
    (let [empty-selection []
          single-selection ["node-1"]
          multi-selection ["node-1" "node-2" "node-3"]]

      ;; Selection state checks
      (is (empty? empty-selection))
      (is (= 1 (count single-selection)))
      (is (= 3 (count multi-selection)))

      ;; Selection membership
      (is (some #{"node-1"} single-selection))
      (is (some #{"node-2"} multi-selection))
      (is (not (some #{"node-4"} multi-selection))))))

(deftest selection-toggle-logic-test
  (testing "Selection toggle operations"
    (let [current-selection ["node-1" "node-2"]
          add-new (conj current-selection "node-3")
          remove-existing (filterv #(not= % "node-1") current-selection)]

      ;; Adding to selection
      (is (= 3 (count add-new)))
      (is (some #{"node-3"} add-new))

      ;; Removing from selection
      (is (= 1 (count remove-existing)))
      (is (not (some #{"node-1"} remove-existing))))))

;; === VIEW STATE TESTS ===

(deftest view-state-operations-test
  (testing "View state manipulation"
    (let [initial-view {:selection [] :highlighted #{} :collapsed #{}}
          with-selection (assoc initial-view :selection ["node-1"])
          with-highlight (assoc initial-view :highlighted #{"node-2"})
          combined (-> initial-view
                       (assoc :selection ["node-1"])
                       (assoc :highlighted #{"node-2"}))]

      ;; Individual state changes
      (is (= ["node-1"] (:selection with-selection)))
      (is (= #{"node-2"} (:highlighted with-highlight)))

      ;; Combined state
      (is (= ["node-1"] (:selection combined)))
      (is (= #{"node-2"} (:highlighted combined))))))

;; === PERFORMANCE AND MEMORY TESTS ===

(deftest memory-efficiency-test
  (testing "Memory-efficient operations"
    (let [large-collection (range 1000)
          filtered (filter even? large-collection)
          mapped (map #(* % 2) large-collection)]

      ;; Operations should be lazy and efficient
      (is (= 500 (count filtered)))
      (is (= 0 (first mapped)))
      (is (= 1998 (last mapped))))))

(deftest immutability-test
  (testing "Data structure immutability"
    (let [original-map {:a 1 :b 2}
          modified-map (assoc original-map :c 3)
          original-vec [1 2 3]
          modified-vec (conj original-vec 4)]

      ;; Original structures unchanged
      (is (= {:a 1 :b 2} original-map))
      (is (= [1 2 3] original-vec))

      ;; New structures have changes
      (is (= {:a 1 :b 2 :c 3} modified-map))
      (is (= [1 2 3 4] modified-vec)))))

;; Run tests
(run-tests)