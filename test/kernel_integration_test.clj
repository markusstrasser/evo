(ns kernel-integration-test
  "Integration test proving the new 3-operation kernel works end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.interpret :as I]
            [core.schema :as S]))

(deftest acceptance-script-test
  (testing "Original acceptance script from specification works perfectly"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
               {:op :create-node :id "a" :type :p :props {:t "A"} :under "root" :at :last}
               {:op :create-node :id "b" :type :p :props {:t "B"} :under "root" :at :last}
               {:op :place :id "b" :under "root" :at {:before "a"}}
               {:op :update-node :id "a" :props {:style {:bold true}}}]
          res (I/interpret db0 txs)]

      ;; No validation issues
      (is (= (:issues res) []))

      ;; Children reordered correctly 
      (is (= (get-in res [:db :children-by-parent "root"]) ["b" "a"]))

      ;; Properties merged correctly
      (is (= (get-in res [:db :nodes "a" :props :style :bold]) true))

      ;; Database validates successfully
      (is (= (:ok? (I/validate (:db res))) true))

      ;; Trace records all operations
      (is (= (count (:trace res)) 5))
      (is (every? #(not (:skipped %)) (:trace res))))))

(deftest api-surface-test
  (testing "Only public API functions are interpret, derive, validate, describe-ops"
    ;; Test interpret
    (let [db (db/empty-db)
          result (I/interpret db [])]
      (is (contains? result :db))
      (is (contains? result :issues))
      (is (contains? result :trace)))

    ;; Test derive 
    (let [db {:nodes {} :children-by-parent {} :derived {}}
          derived (db/derive db)]
      (is (contains? derived :derived)))

    ;; Test validate
    (let [db (db/empty-db)
          validation (I/validate db)]
      (is (contains? validation :ok?))
      (is (contains? validation :errors)))

    ;; Test describe-ops
    (let [schemas (S/describe-ops)]
      (is (contains? schemas :Db))
      (is (contains? schemas :Op))
      (is (contains? schemas :Create))
      (is (contains? schemas :Place))
      (is (contains? schemas :Update)))))

(deftest canonical-value-test
  (testing "Kernel operates over single canonical value with correct shape"
    (let [db (db/empty-db)]
      (is (= (set (keys db)) #{:nodes :children-by-parent :derived}))

      ;; After derive, derived contains expected keys
      (let [derived-db (db/derive db)]
        (is (contains? (:derived derived-db) :parent-of))
        (is (contains? (:derived derived-db) :index-of))
        (is (contains? (:derived derived-db) :prev-id-of))
        (is (contains? (:derived derived-db) :next-id-of))))))

(deftest three-ops-only-test
  (testing "Only three operations are supported: create-node, place, update-node"
    (let [db0 (db/empty-db)
          db-with-node (:db (I/interpret db0 [{:op :create-node :id "x" :type :div :props {} :under :doc :at :last}]))]
      ;; Valid operations work
      (is (empty? (:issues (I/interpret db0 [{:op :create-node :id "x" :type :div :props {} :under :doc :at :last}]))))
      (is (empty? (:issues (I/interpret db-with-node [{:op :place :id "x" :under :doc :at :first}]))))
      (is (empty? (:issues (I/interpret db-with-node [{:op :update-node :id "x" :props {:new true}}]))))

      ;; Invalid operations are rejected
      (is (seq (:issues (I/interpret db0 [{:op :prune :id "x"}]))))
      (is (seq (:issues (I/interpret db0 [{:op :delete :id "x"}]))))
      (is (seq (:issues (I/interpret db0 [{:op :unknown :id "x"}])))))))

(deftest deterministic-test
  (testing "All operations are deterministic and reproducible"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
               {:op :create-node :id "a" :type :p :props {} :under "root" :at :last}
               {:op :create-node :id "b" :type :p :props {} :under "root" :at :last}]
          result1 (I/interpret db0 txs)
          result2 (I/interpret db0 txs)]
      (is (= (:db result1) (:db result2)))
      (is (= (:issues result1) (:issues result2)))
      (is (= (:trace result1) (:trace result2))))))

(deftest edge-cases-test
  (testing "Edge cases are handled gracefully"
    (let [db0 (db/empty-db)]
      ;; Empty transaction
      (let [result (I/interpret db0 [])]
        (is (empty? (:issues result)))
        (is (empty? (:trace result))))

      ;; Placing at negative index gets clamped
      (let [result (I/interpret db0 [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
                                     {:op :create-node :id "a" :type :p :props {} :under "root" :at -5}])]
        (is (empty? (:issues result)))
        (is (= (get-in result [:db :children-by-parent "root"]) ["a"])))

      ;; Placing at high index gets clamped
      (let [result (I/interpret db0 [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
                                     {:op :create-node :id "a" :type :p :props {} :under "root" :at 1000}])]
        (is (empty? (:issues result)))
        (is (= (get-in result [:db :children-by-parent "root"]) ["a"])))

      ;; Reference to non-existent sibling in :before/:after
      (let [result (I/interpret db0 [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
                                     {:op :create-node :id "a" :type :p :props {} :under "root" :at {:before "nonexistent"}}])]
        (is (empty? (:issues result)))
        (is (= (get-in result [:db :children-by-parent "root"]) ["a"]))))))