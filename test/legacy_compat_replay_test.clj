(ns legacy-compat-replay-test
  "Golden replay tests using legacy compatibility layer."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.interpret :as interpret]
            [legacy.compat :as compat]))

(defn load-example [filename]
  (-> (str "legacy/examples/" filename)
      slurp
      edn/read-string))

(deftest basic-legacy-replay
  "Replay basic legacy operations through compat layer."
  (testing "basic.edn legacy trace"
    (let [legacy-ops (load-example "basic.edn")
          core-ops (compat/lower legacy-ops)
          result (interpret/interpret (db/empty-db) core-ops)]

      ;; Should complete without issues
      (is (empty? (:issues result))
          (str "Should have no issues, got: " (:issues result)))

      ;; Should have correct tree structure (b before a)
      (is (= ["b" "a"] (get-in result [:db :children-by-parent "root"]))
          "Should have b before a in root")

      ;; Should have updated props
      (is (= true (get-in result [:db :nodes "a" :props :style :bold]))
          "Node a should be bold")

      ;; Should have refs encoded in props
      (is (= #{"b"} (get-in result [:db :nodes "a" :props :refs :mentions]))
          "Node a should have ref to b"))))

(deftest reparent-legacy-replay
  "Replay reparenting operations through compat layer."
  (testing "reparent.edn legacy trace"
    (let [legacy-ops (load-example "reparent.edn")
          core-ops (compat/lower legacy-ops)
          result (interpret/interpret (db/empty-db) core-ops)]

      ;; Should complete without issues
      (is (empty? (:issues result))
          (str "Should have no issues, got: " (:issues result)))

      ;; child1 should be moved to root
      (is (= "root" (get-in result [:db :parent-by-child "child1"]))
          "child1 should be under root")

      ;; child2 should remain under parent
      (is (= "parent" (get-in result [:db :parent-by-child "child2"]))
          "child2 should be under parent"))))

(deftest unknown-ops-handling
  "Unknown legacy ops should be preserved as breadcrumbs."
  (testing "unknown op preservation"
    (let [ops [{:op :unknown-legacy-op :data "test"}]
          lowered (compat/lower ops)]
      (is (= [{:op :__unknown__ :raw {:op :unknown-legacy-op :data "test"}}]
             lowered)
          "Unknown ops should be wrapped as breadcrumbs"))))