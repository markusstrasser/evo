(ns acceptance-test
  "End-to-end acceptance tests for the refactored system."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [core.db :as db]
            [core.interpret :as interpret]
            [legacy.compat :as compat]
            [labs.graph.derive :as graph]))

(deftest core-three-op-workflow
  "Test core 3-op kernel with direct operations."
  (testing "Core kernel basic workflow"
    (let [ops [{:op :create-node :id "test1" :type :text :props {:content "Hello"}}
               {:op :place :id "test1" :under "root" :at :last}
               {:op :update-node :id "test1" :props {:content "World"}}]
          result (interpret/interpret (db/empty-db) ops)]

      (is (empty? (:issues result)) "Should have no issues")
      (is (= "World" (get-in result [:db :nodes "test1" :props :content]))
          "Should have updated content")
      (is (= ["test1"] (get-in result [:db :children-by-parent "root"]))
          "Should be placed under root"))))

(deftest legacy-compat-integration
  "Test legacy operations through compat layer."
  (testing "Legacy ops lowered to core"
    (let [legacy-ops [{:op :insert :id "a" :type :text :props {:content "Hello"} :under "root"}
                      {:op :add-ref :source-id "a" :target-id "root" :relation :parent}]
          core-ops (compat/lower legacy-ops)
          result (interpret/interpret (db/empty-db) core-ops)]

      (is (empty? (:issues result)) "Should have no issues")
      (is (= "Hello" (get-in result [:db :nodes "a" :props :content]))
          "Should preserve content")
      (is (= #{"root"} (get-in result [:db :nodes "a" :props :refs :parent]))
          "Should encode refs in props"))))

(deftest labs-graph-derive
  "Test labs graph functionality over core data."
  (testing "Graph derivation from node props"
    (let [db {:nodes {"a" {:props {:refs {:mentions #{"b" "c"}}}}
                      "b" {:props {}}
                      "c" {:props {}}}}
          edges (graph/edge-index db)
          neighbors (graph/neighbors db "a" :mentions)]

      (is (= #{"b" "c"} (get-in edges [:mentions "a"]))
          "Should extract edge index from props")
      (is (= #{"b" "c"} neighbors)
          "Should return neighbors from derived index"))))

(deftest system-integration
  "Full system test: legacy -> core -> labs."
  (testing "End-to-end flow"
    (let [;; Start with legacy operations
          legacy-ops [{:op :insert :id "post" :type :text :props {:content "Hello world"} :under "root"}
                      {:op :insert :id "comment" :type :text :props {:content "Nice post!"} :under "root"}
                      {:op :add-ref :source-id "comment" :target-id "post" :relation :replies-to}]

          ;; Lower through compat
          core-ops (compat/lower legacy-ops)

          ;; Interpret with core
          result (interpret/interpret (db/empty-db) core-ops)

          ;; Query with labs
          edges (graph/edge-index (:db result))
          replies (graph/neighbors (:db result) "comment" :replies-to)]

      (is (empty? (:issues result)) "Should complete without issues")
      (is (= #{"post"} replies) "Should find graph relationship")
      (is (= 2 (count (get-in result [:db :children-by-parent "root"])))
          "Should have both nodes under root"))))