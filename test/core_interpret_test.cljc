(ns core-interpret-test
  "Tests for core.interpret - invariant-based, implementation-agnostic tests"
  (:require [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.interpret :as interp]))

(defn create-op [id type] {:op :create-node :id id :type type :props {}})
(defn place-op [id under at] {:op :place :id id :under under :at at})
(defn update-op [id props] {:op :update-node :id id :props props})

(defn apply-ops
  "Apply ops and return final db. Fails test if validation issues occur."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (let [result (interp/interpret db ops)]
     (when (seq (:issues result))
       (is false (str "Unexpected validation issues: " (:issues result))))
     (:db result))))

(defn apply-ops-expect-failure
  "Apply ops expecting validation failure. Returns issues vector."
  ([ops] (apply-ops-expect-failure (db/empty-db) ops))
  ([db ops]
   (let [result (interp/interpret db ops)]
     (is (seq (:issues result)) "Expected validation issues")
     (:issues result))))

(defn node-exists? [db id] (contains? (:nodes db) id))
(defn node-type [db id] (get-in db [:nodes id :type]))
(defn node-props [db id] (get-in db [:nodes id :props]))
(defn children-of [db parent] (get (:children-by-parent db) parent []))
(defn parent-of [db id] (get-in db [:derived :parent-of id]))
(defn db-valid? [db] (:ok? (db/validate db)))

(deftest test-create-node
  (testing "creating a node makes it exist with correct type"
    (let [db (apply-ops [(create-op "a" :div)])]
      (is (node-exists? db "a"))
      (is (= :div (node-type db "a")))
      (is (db-valid? db)))))

(deftest test-create-duplicate-rejected
  (testing "creating duplicate node is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(create-op "a" :span)])]
      (is (= :duplicate-create (-> issues first :issue))))))

(deftest test-place-establishes-parent-child
  (testing "placing a node establishes parent-child relationship"
    (let [db (apply-ops [(create-op "a" :div)
                         (place-op "a" :doc :first)])]
      (is (= ["a"] (children-of db :doc)))
      (is (= :doc (parent-of db "a")))
      (is (db-valid? db)))))

(deftest test-place-positions
  (testing "place respects :first, :last, and numeric positions"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" :doc :last)
                         (place-op "c" :doc 1)])]
      (is (= ["a" "c" "b"] (children-of db :doc)))
      (is (db-valid? db)))))

(deftest test-update-merges-props
  (testing "update merges properties into node"
    (let [db (apply-ops [(create-op "a" :div)
                         (update-op "a" {:x 1 :y 2})])]
      (is (= {:x 1 :y 2} (node-props db "a")))
      (is (db-valid? db))))

  (testing "multiple updates accumulate properties"
    (let [db (apply-ops [(create-op "a" :div)
                         (update-op "a" {:x 1})
                         (update-op "a" {:y 2})
                         (update-op "a" {:z 3})])]
      (is (= {:x 1 :y 2 :z 3} (node-props db "a")))
      (is (db-valid? db)))))

(deftest test-idempotent-place
  (testing "placing node at same position is idempotent"
    (let [db1 (apply-ops [(create-op "a" :div)
                          (place-op "a" :doc :first)])
          db2 (apply-ops db1 [(place-op "a" :doc :first)])]
      (is (= db1 db2) "DB unchanged by redundant place")
      (is (db-valid? db2)))))

(deftest test-reject-missing-node
  (testing "placing non-existent node is rejected"
    (let [issues (apply-ops-expect-failure [(place-op "missing" :doc :first)])]
      (is (= :node-not-found (-> issues first :issue))))))

(deftest test-reject-missing-parent
  (testing "placing under non-existent parent is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(place-op "a" "missing-parent" :first)])]
      (is (= :parent-not-found (-> issues first :issue))))))

(deftest test-reject-self-cycle
  (testing "placing node under itself is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(place-op "a" "a" :first)])]
      (is (= :cycle-detected (-> issues first :issue))))))

(deftest test-reject-descendant-cycle
  (testing "placing node under its descendant is rejected"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)])
          issues (apply-ops-expect-failure db [(place-op "a" "b" :first)])]
      (is (= :cycle-detected (-> issues first :issue))))))

(deftest test-reject-invalid-anchor
  (testing "anchor node must be sibling under same parent"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)])
          issues (apply-ops-expect-failure db [(place-op "b" :trash {:before "a"})])]
      (is (= :anchor-not-sibling (-> issues first :issue))))))

(deftest test-build-tree
  (testing "can build multi-level tree structure"
    (let [db (apply-ops [(create-op "root" :div)
                         (create-op "header" :header)
                         (create-op "content" :div)
                         (place-op "root" :doc :first)
                         (place-op "header" "root" :first)
                         (place-op "content" "root" :last)])]
      (is (node-exists? db "root"))
      (is (node-exists? db "header"))
      (is (node-exists? db "content"))
      (is (= ["root"] (children-of db :doc)))
      (is (= ["header" "content"] (children-of db "root")))
      (is (= "root" (parent-of db "header")))
      (is (= "root" (parent-of db "content")))
      (is (db-valid? db)))))

(deftest test-move-subtree
  (testing "moving node preserves its children"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)
                         (place-op "c" :doc :last)
                         (place-op "a" "c" :first)])]
      (is (= ["c"] (children-of db :doc)))
      (is (= ["a"] (children-of db "c")))
      (is (= ["b"] (children-of db "a")))
      (is (= "c" (parent-of db "a")))
      (is (= "a" (parent-of db "b")))
      (is (db-valid? db)))))

(deftest test-complex-sequence
  (testing "complex operation sequence produces correct final state"
    (let [db (apply-ops [(create-op "a" :div)
                         (place-op "a" :doc :first)
                         (update-op "a" {:class "container"})
                         (create-op "b" :span)
                         (place-op "b" "a" :first)
                         (update-op "b" {:text "Hello"})
                         (update-op "a" {:id "main"})
                         (create-op "c" :p)
                         (place-op "c" "a" :last)])]
      (is (= {:class "container" :id "main"} (node-props db "a")))
      (is (= {:text "Hello"} (node-props db "b")))
      (is (= ["b" "c"] (children-of db "a")))
      (is (db-valid? db)))))

(deftest test-invariant-parent-child-bidirectional
  (testing "parent-of and children-by-parent stay in sync"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)])]
      (is (= "a" (parent-of db "b")))
      (is (= ["b"] (children-of db "a")))
      (is (db-valid? db)))))

(deftest test-invariant-transactional-failure
  (testing "failed operation halts pipeline - no partial application"
    (let [db (apply-ops [(create-op "a" :div)])
          result (interp/interpret db [(update-op "a" {:x 1})
                                       (place-op "missing" :doc :first)
                                       (update-op "a" {:y 2})])]
      (is (seq (:issues result)))
      (is (= {:x 1} (node-props (:db result) "a")) "Only op before failure applied"))))

(deftest test-invariant-no-orphans
  (testing "all nodes except roots have parents"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)
                         (place-op "c" "a" :last)])
          all-nodes (keys (:nodes db))
          nodes-with-parents (keys (get-in db [:derived :parent-of]))]
      (is (= (set all-nodes) (set nodes-with-parents)))
      (is (db-valid? db)))))