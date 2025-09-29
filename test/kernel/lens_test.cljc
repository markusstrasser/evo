(ns labs.lens-test
  (:require [labs.lens :as lens]
            [clojure.string :as str]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

;; Test data setup
(def test-db
  {:nodes {"root" {:type :root :props {}}
           "parent" {:type :div :props {:class "container"}}
           "child1" {:type :span :props {:class "first"}}
           "child2" {:type :span :props {:class "second"}}
           "grandchild" {:type :p :props {}}}
   :child-ids/by-parent {"root" ["parent"]
                         "parent" ["child1" "child2"]
                         "child2" ["grandchild"]}
   :derived {:parent-id-of {"parent" "root" "child1" "parent" "child2" "parent" "grandchild" "child2"}
             :index-of {"parent" 0 "child1" 0 "child2" 1 "grandchild" 0}}})

(deftest test-children-of
  (testing "children-of returns correct child lists"
    (is (= ["parent"] (lens/children-of test-db "root")))
    (is (= ["child1" "child2"] (lens/children-of test-db "parent")))
    (is (= ["grandchild"] (lens/children-of test-db "child2")))
    (is (= [] (lens/children-of test-db "child1"))) ; leaf node
    (is (= [] (lens/children-of test-db "nonexistent"))))) ; missing node

(deftest test-parent-of
  (testing "parent-of returns correct parent IDs"
    (is (= nil (lens/parent-of test-db "root"))) ; root has no parent
    (is (= "root" (lens/parent-of test-db "parent")))
    (is (= "parent" (lens/parent-of test-db "child1")))
    (is (= "parent" (lens/parent-of test-db "child2")))
    (is (= "child2" (lens/parent-of test-db "grandchild")))
    (is (= nil (lens/parent-of test-db "nonexistent"))))) ; missing node

(deftest test-index-of
  (testing "index-of returns correct indices"
    (is (= 0 (lens/index-of test-db "parent")))
    (is (= 0 (lens/index-of test-db "child1")))
    (is (= 1 (lens/index-of test-db "child2")))
    (is (= 0 (lens/index-of test-db "grandchild")))
    (is (= -1 (lens/index-of test-db "nonexistent"))))) ; missing node

(deftest test-position-of
  (testing "position-of returns correct position maps"
    (is (= {:parent-id "root" :index 0} (lens/position-of test-db "parent")))
    (is (= {:parent-id "parent" :index 1} (lens/position-of test-db "child2")))
    (is (= {:parent-id nil :index -1} (lens/position-of test-db "root")))
    (is (= {:parent-id nil :index -1} (lens/position-of test-db "nonexistent")))))

(deftest test-path-to-root
  (testing "path-to-root returns correct paths"
    (is (= ["root"] (lens/path-to-root test-db "root")))
    (is (= ["parent" "root"] (lens/path-to-root test-db "parent")))
    (is (= ["child1" "parent" "root"] (lens/path-to-root test-db "child1")))
    (is (= ["grandchild" "child2" "parent" "root"] (lens/path-to-root test-db "grandchild")))
    (is (= ["nonexistent"] (lens/path-to-root test-db "nonexistent"))))) ; missing node

(deftest test-siblings
  (testing "siblings returns correct sibling lists"
    (is (= ["parent"] (lens/siblings test-db "parent"))) ; only child
    (is (= ["child1" "child2"] (lens/siblings test-db "child1")))
    (is (= ["child1" "child2"] (lens/siblings test-db "child2")))
    (is (= ["grandchild"] (lens/siblings test-db "grandchild"))) ; only child
    (is (= [] (lens/siblings test-db "root"))) ; root has no siblings
    (is (= [] (lens/siblings test-db "nonexistent"))))) ; missing node

(deftest test-prev-next-id
  (testing "prev-id and next-id navigation"
    ;; prev-id tests
    (is (= nil (lens/prev-id test-db "child1"))) ; first sibling
    (is (= "child1" (lens/prev-id test-db "child2"))) ; has previous
    (is (= nil (lens/prev-id test-db "parent"))) ; only child
    (is (= nil (lens/prev-id test-db "root"))) ; root
    (is (= nil (lens/prev-id test-db "nonexistent"))) ; missing

    ;; next-id tests
    (is (= "child2" (lens/next-id test-db "child1"))) ; has next
    (is (= nil (lens/next-id test-db "child2"))) ; last sibling
    (is (= nil (lens/next-id test-db "parent"))) ; only child
    (is (= nil (lens/next-id test-db "root"))) ; root
    (is (= nil (lens/next-id test-db "nonexistent"))))) ; missing

(deftest test-predicates
  (testing "node existence and type predicates"
    ;; node-exists?
    (is (true? (lens/node-exists? test-db "root")))
    (is (true? (lens/node-exists? test-db "grandchild")))
    (is (false? (lens/node-exists? test-db "nonexistent")))

    ;; is-root?
    (is (true? (lens/is-root? test-db "root")))
    (is (false? (lens/is-root? test-db "parent")))
    (is (false? (lens/is-root? test-db "nonexistent")))

    ;; is-leaf?
    (is (false? (lens/is-leaf? test-db "root")))
    (is (false? (lens/is-leaf? test-db "parent")))
    (is (true? (lens/is-leaf? test-db "child1"))) ; no children
    (is (true? (lens/is-leaf? test-db "grandchild"))) ; no children
    (is (true? (lens/is-leaf? test-db "nonexistent"))))) ; missing = empty children

(deftest test-depth-of
  (testing "depth-of returns correct depths"
    (is (= 0 (lens/depth-of test-db "root")))
    (is (= 1 (lens/depth-of test-db "parent")))
    (is (= 2 (lens/depth-of test-db "child1")))
    (is (= 2 (lens/depth-of test-db "child2")))
    (is (= 3 (lens/depth-of test-db "grandchild")))
    (is (= 0 (lens/depth-of test-db "nonexistent"))))) ; missing path = [nonexistent] = depth 0

(deftest test-node-data-access
  (testing "node type and props access"
    ;; node-type
    (is (= :root (lens/node-type test-db "root")))
    (is (= :div (lens/node-type test-db "parent")))
    (is (= :span (lens/node-type test-db "child1")))
    (is (= nil (lens/node-type test-db "nonexistent")))

    ;; node-props
    (is (= {} (lens/node-props test-db "root")))
    (is (= {:class "container"} (lens/node-props test-db "parent")))
    (is (= {:class "first"} (lens/node-props test-db "child1")))
    (is (= {} (lens/node-props test-db "nonexistent"))))) ; default empty map

(deftest test-explain
  (testing "explain provides human-readable descriptions"
    (let [root-desc (lens/explain test-db "root")
          child-desc (lens/explain test-db "child2")
          grandchild-desc (lens/explain test-db "grandchild")
          missing-desc (lens/explain test-db "nonexistent")]

      ;; Check that key information is present
      (is (clojure.string/includes? root-desc "id=root"))
      (is (clojure.string/includes? root-desc "[ROOT]"))
      (is (clojure.string/includes? root-desc "type=:root"))

      (is (clojure.string/includes? child-desc "id=child2"))
      (is (clojure.string/includes? child-desc "index=1"))
      (is (clojure.string/includes? child-desc "type=:span"))
      (is (clojure.string/includes? child-desc "/root/parent/child2"))

      (is (clojure.string/includes? grandchild-desc "[LEAF]"))
      (is (clojure.string/includes? grandchild-desc "index=0"))

      (is (clojure.string/includes? missing-desc "[NOT FOUND]")))))

(deftest test-round-trip-navigation
  (testing "prev/next navigation round-trips correctly"
    ;; For any node with neighbors, prev->next and next->prev should work
    (let [child1-next (lens/next-id test-db "child1")
          child2-prev (lens/prev-id test-db "child2")]
      (is (= "child2" child1-next))
      (is (= "child1" child2-prev))
      ;; Round trip
      (is (= "child1" (lens/prev-id test-db child1-next)))
      (is (= "child2" (lens/next-id test-db child2-prev))))))

(deftest test-integration-assertions
  (testing "integration assertions from spec requirements"
    ;; For every node: index matches position in siblings list
    (doseq [node-id (keys (:nodes test-db))]
      (let [siblings (lens/siblings test-db node-id)
            index (lens/index-of test-db node-id)]
        (when (>= index 0) ; Only check if index is valid
          (is (= node-id (nth siblings index nil))
              (str "Index mismatch for " node-id ": index=" index " but siblings=" siblings)))))

    ;; For every parent: children count matches vector length
    (doseq [[parent-id children] (:child-ids/by-parent test-db)]
      (is (= (count children) (count (lens/children-of test-db parent-id)))
          (str "Children count mismatch for " parent-id)))

    ;; Path consistency: every node in path exists
    (doseq [node-id (keys (:nodes test-db))]
      (let [path (lens/path-to-root test-db node-id)]
        (doseq [path-node path]
          (when (not= path-node node-id) ; Don't check the original node if it doesn't exist
            (is (lens/node-exists? test-db path-node)
                (str "Path contains non-existent node: " path-node " in path " path))))))))

(comment
  ;; REPL test examples:

  ;; Run individual tests
  (test-children-of)
  (test-path-to-root)
  (test-explain)

  ;; Run all tests
  #?(:clj (clojure.test/run-tests)
     :cljs (cljs.test/run-tests)))