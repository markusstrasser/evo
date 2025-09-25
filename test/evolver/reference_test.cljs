(ns evolver.reference-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [evolver.kernel :as kernel]
            [evolver.schemas :as schemas]))

(deftest reference-system-test
  (testing "Adding and removing references"
    (let [db kernel/db
          db-with-ref (kernel/add-reference db {:from-node-id "title" :to-node-id "p1-select"})
          references (kernel/get-references db-with-ref "p1-select")
          db-without-ref (kernel/remove-reference db-with-ref {:from-node-id "title" :to-node-id "p1-select"})
          references-after (kernel/get-references db-without-ref "p1-select")]

      (is (= #{"title"} references) "Should have one reference")
      (is (= #{} references-after) "Should have no references after removal")))

  (testing "Schema validation with references"
    (let [db kernel/db
          db-with-ref (kernel/add-reference db {:from-node-id "title" :to-node-id "p1-select"})]
      (is (schemas/validate-db db-with-ref) "Database with references should be valid")))

  (testing "Safe apply command for references"
    (let [db kernel/db
          command {:op :add-reference :from-node-id "title" :to-node-id "p1-select"}
          result (kernel/safe-apply-command db command)
          references (kernel/get-references result "p1-select")]
      (is (= #{"title"} references) "Safe apply should work for references")))

  (testing "Undo/redo for references"
    (let [db kernel/db
          command {:op :add-reference :from-node-id "title" :to-node-id "p1-select"}
          db-after-add (kernel/safe-apply-command db command)
          db-after-undo (kernel/safe-apply-command db-after-add {:op :undo})
          db-after-redo (kernel/safe-apply-command db-after-undo {:op :redo})
          refs-after-add (kernel/get-references db-after-add "p1-select")
          refs-after-undo (kernel/get-references db-after-undo "p1-select")
          refs-after-redo (kernel/get-references db-after-redo "p1-select")]

      (is (= #{"title"} refs-after-add) "Should have reference after add")
      (is (= #{} refs-after-undo) "Should have no references after undo")
      (is (= #{"title"} refs-after-redo) "Should have reference back after redo")))

  (testing "Multiple references and bidirectional relationships"
    (let [db kernel/db
          db1 (kernel/add-reference db {:from-node-id "title" :to-node-id "p1-select"})
          db2 (kernel/add-reference db1 {:from-node-id "p1-select" :to-node-id "p2-high"})
          refs-p1 (kernel/get-references db2 "p1-select")
          refs-p2 (kernel/get-references db2 "p2-high")
          referenced-by-title (kernel/get-referenced-by db2 "title")]

      (is (= #{"title"} refs-p1) "p1-select should be referenced by title")
      (is (= #{"p1-select"} refs-p2) "p2-high should be referenced by p1-select")
      (is (= #{"p1-select"} referenced-by-title) "title should reference p1-select")))

  (testing "Reference integrity after node operations"
    (let [db kernel/db
          db-with-ref (kernel/add-reference db {:from-node-id "title" :to-node-id "p1-select"})
          db-after-delete (kernel/safe-apply-command db-with-ref {:op :delete :node-id "p1-select" :recursive true})
          refs-after-delete (kernel/get-references db-after-delete "p1-select")]

      (is (= #{} refs-after-delete) "References should be cleaned up when referenced node is deleted")))

  (testing "Reference validation and error handling"
    (let [db kernel/db
          invalid-command {:op :add-reference :from-node-id "nonexistent" :to-node-id "p1-select"}]
      (is (thrown? js/Error (kernel/safe-apply-command db invalid-command))
          "Should throw error for invalid reference"))))