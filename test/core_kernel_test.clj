(ns core-kernel-test
  "Core kernel functionality tests."
  (:require [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.interpret :as I]
            [core.schema :as S]))

(deftest test-empty-db
  (testing "Empty database structure"
    (let [db (db/empty-db)]
      (is (= (:nodes db) {}))
      (is (= (:children-by-parent db) {}))
      (is (= (:derived db) {})))))

(deftest test-derive-functionality
  (testing "Derive builds correct indexes"
    (let [db {:nodes {"a" {:type :p :props {}}
                      "b" {:type :p :props {}}
                      "root" {:type :doc :props {}}}
              :children-by-parent {"root" ["a" "b"]}
              :derived {}}
          derived-db (db/derive db)]
      (is (= (get-in derived-db [:derived :parent-of])
             {"a" "root" "b" "root"}))
      (is (= (get-in derived-db [:derived :index-of])
             {"a" 0 "b" 1}))
      ;; prev-id-of and next-id-of include entries for all nodes, including nil values
      (is (= (get-in derived-db [:derived :prev-id-of])
             {"a" nil "b" "a"}))
      (is (= (get-in derived-db [:derived :next-id-of])
             {"a" "b" "b" nil})))))

(deftest test-create-and-order
  (testing "Create nodes and reorder them"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
               {:op :create-node :id "a" :type :p :props {:t "A"} :under "root" :at :last}
               {:op :create-node :id "b" :type :p :props {:t "B"} :under "root" :at :last}
               {:op :place :id "b" :under "root" :at {:before "a"}}]
          result (I/interpret db0 txs)]
      (is (empty? (:issues result)))
      (is (= (get-in result [:db :children-by-parent "root"]) ["b" "a"]))
      (is (= (get-in result [:db :derived :index-of "b"]) 0))
      (is (= (get-in result [:db :derived :index-of "a"]) 1)))))

(deftest test-idempotent-place
  (testing "Applying same place operation twice yields same result"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
               {:op :create-node :id "a" :type :p :props {} :under "root" :at :last}
               {:op :create-node :id "b" :type :p :props {} :under "root" :at :last}
               {:op :place :id "b" :under "root" :at {:before "a"}}]
          result1 (I/interpret db0 txs)
          result2 (I/interpret (:db result1) [{:op :place :id "b" :under "root" :at {:before "a"}}])]
      (is (= (:db result1) (:db result2))))))

(deftest test-update-merges-props
  (testing "Update operation merges properties"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "a" :type :p :props {:t "A" :style {:bold false}} :under :doc :at :last}
               {:op :update-node :id "a" :props {:style {:bold true}}}]
          result (I/interpret db0 txs)]
      (is (empty? (:issues result)))
      (is (= (get-in result [:db :nodes "a" :props])
             {:t "A" :style {:bold true}})))))

(deftest test-validation-rejects-unknown-ops
  (testing "Unknown operations are rejected with issues"
    (let [db0 (db/empty-db)
          txs [{:op :prune :id "x"}]
          result (I/interpret db0 txs)]
      (is (= (:db result) (db/derive db0)))
      (is (= (count (:issues result)) 1))
      (is (= (get-in result [:issues 0 :issue]) :schema/invalid)))))

(deftest test-validate-invariants
  (testing "Validation flags duplicate children"
    (let [bad-db {:nodes {"a" {:type :p :props {}}
                          "b" {:type :p :props {}}}
                  :children-by-parent {"root" ["a" "a" "b"]}
                  :derived {}}
          validation (I/validate bad-db)]
      (is (false? (:ok? validation)))
      (is (some #(= (:issue %) :siblings/duplicate) (:errors validation))))))

(deftest test-placement-positions
  (testing "All placement position types work correctly"
    (let [db0 (db/empty-db)
          setup-txs [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
                     {:op :create-node :id "a" :type :p :props {} :under "root" :at :last}
                     {:op :create-node :id "b" :type :p :props {} :under "root" :at :last}
                     {:op :create-node :id "c" :type :p :props {} :under "root" :at :last}]
          setup-result (I/interpret db0 setup-txs)]

      (testing ":first placement"
        (let [result (I/interpret (:db setup-result)
                                  [{:op :create-node :id "new" :type :p :props {} :under "root" :at :first}])]
          (is (= (first (get-in result [:db :children-by-parent "root"])) "new"))))

      (testing ":last placement"
        (let [result (I/interpret (:db setup-result)
                                  [{:op :create-node :id "new" :type :p :props {} :under "root" :at :last}])]
          (is (= (last (get-in result [:db :children-by-parent "root"])) "new"))))

      (testing "integer index placement"
        (let [result (I/interpret (:db setup-result)
                                  [{:op :create-node :id "new" :type :p :props {} :under "root" :at 1}])]
          (is (= (nth (get-in result [:db :children-by-parent "root"]) 1) "new"))))

      (testing "{:after id} placement"
        (let [result (I/interpret (:db setup-result)
                                  [{:op :create-node :id "new" :type :p :props {} :under "root" :at {:after "a"}}])]
          (is (= (get-in result [:db :children-by-parent "root"]) ["a" "new" "b" "c"])))))))

(deftest test-missing-parent-validation
  (testing "Operations with missing parents are rejected"
    (let [db0 (db/empty-db)
          txs [{:op :create-node :id "a" :type :p :props {} :under "nonexistent" :at :last}]
          result (I/interpret db0 txs)]
      (is (= (count (:issues result)) 1))
      (is (= (get-in result [:issues 0 :issue]) :parent/missing)))))

(deftest test-missing-id-validation
  (testing "Operations on non-existent nodes are rejected with suggestions"
    (let [db0 (db/empty-db)
          txs [{:op :place :id "nonexistent" :under :doc :at :last}]
          result (I/interpret db0 txs)]
      (is (= (count (:issues result)) 1))
      (is (= (get-in result [:issues 0 :issue]) :id/missing))
      (is (seq (get-in result [:issues 0 :suggest]))))))

(deftest test-small-sibling-lists
  (testing "Small sibling lists maintain correct order and have no duplicates"
    (let [db0 (db/empty-db)
          setup-tx [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}]
          setup-result (I/interpret db0 setup-tx)
          create-txs (for [i (range 10)]
                       {:op :create-node :id (str "node-" i) :type :p :props {} :under "root" :at :last})
          result (I/interpret (:db setup-result) create-txs)]
      (is (empty? (:issues result)))
      (let [children (get-in result [:db :children-by-parent "root"])]
        (is (= (count children) 10))
        (is (= children (distinct children)))))))