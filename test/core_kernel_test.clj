(ns core-kernel-test
  "Unit tests for the three-op kernel core functionality."
  (:require [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.ops :as ops]
            [core.interpret :as interp]
            [core.schema :as schema]))

(deftest empty-db-test
  (testing "Empty database has correct structure"
    (let [db (db/empty-db)]
      (is (= {:nodes {}
              :children-by-parent {}
              :roots #{:doc :trash}
              :derived {:parent-of {}
                        :index-of {}
                        :prev-id-of {}
                        :next-id-of {}
                        :pre {}
                        :post {}
                        :id-by-pre {}}}
             db)))))

(deftest create-node-test
  (testing "Create node operation"
    (let [db (db/empty-db)
          updated (ops/create-node db "node1" :text {:content "Hello"})]
      (is (contains? (:nodes updated) "node1"))
      (is (= {:type :text :props {:content "Hello"}}
             (get-in updated [:nodes "node1"]))))

    (testing "Idempotent - creating existing node does nothing"
      (let [db (-> (db/empty-db)
                   (ops/create-node "node1" :text {:content "Hello"}))
            updated (ops/create-node db "node1" :text {:content "Different"})]
        (is (= {:content "Hello"}
               (get-in updated [:nodes "node1" :props])))))))

(deftest place-node-test
  (testing "Place node under root"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {:content "Hello"})
                 (ops/place "node1" :doc :last))
          derived (db/derive-indexes db)]
      (is (= ["node1"] (get-in db [:children-by-parent :doc])))
      (is (= :doc (get-in derived [:derived :parent-of "node1"])))
      (is (= 0 (get-in derived [:derived :index-of "node1"])))))

  (testing "Place with different anchor types"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {})
                 (ops/create-node "node2" :text {})
                 (ops/create-node "node3" :text {})
                 (ops/place "node1" :doc :last)
                 (ops/place "node2" :doc :first)
                 (ops/place "node3" :doc {:after "node2"}))]
      (is (= ["node2" "node3" "node1"]
             (get-in db [:children-by-parent :doc])))))

  (testing "Move node between parents"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {})
                 (ops/place "node1" :doc :last)
                 (ops/place "node1" :trash :first))]
      (is (= ["node1"] (get-in db [:children-by-parent :trash])))
      (is (not (contains? (:children-by-parent db) :doc))))))

(deftest update-node-test
  (testing "Update node properties"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {:content "Hello" :style {:color "red"}}))
          updated (ops/update-node db "node1" {:content "World" :style {:size "large"}})]
      (is (= {:content "World"
              :style {:color "red" :size "large"}}
             (get-in updated [:nodes "node1" :props])))))

  (testing "Update non-existent node is no-op"
    (let [db (db/empty-db)
          updated (ops/update-node db "missing" {:content "test"})]
      (is (= db updated)))))

(deftest derive-test
  (testing "Derive computes correct relationships"
    (let [db (-> (db/empty-db)
                 (ops/create-node "parent" :container {})
                 (ops/create-node "child1" :text {})
                 (ops/create-node "child2" :text {})
                 (ops/place "parent" :doc :last)
                 (ops/place "child1" "parent" :first)
                 (ops/place "child2" "parent" :last))
          derived-db (db/derive-indexes db)
          derived (:derived derived-db)]

      (is (= :doc (get-in derived [:parent-of "parent"])))
      (is (= "parent" (get-in derived [:parent-of "child1"])))
      (is (= "parent" (get-in derived [:parent-of "child2"])))

      (is (= 0 (get-in derived [:index-of "parent"])))
      (is (= 0 (get-in derived [:index-of "child1"])))
      (is (= 1 (get-in derived [:index-of "child2"])))

      (is (nil? (get-in derived [:prev-id-of "child1"])))
      (is (= "child1" (get-in derived [:prev-id-of "child2"])))
      (is (= "child2" (get-in derived [:next-id-of "child1"])))
      (is (nil? (get-in derived [:next-id-of "child2"]))))))

(deftest validation-test
  (testing "Valid database passes validation"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {})
                 (ops/place "node1" :doc :last)
                 (db/derive-indexes))
          result (db/validate db)]
      (is (:ok? result))
      (is (empty? (:errors result)))))

  (testing "Invalid database fails validation"
    (let [invalid-db {:nodes {"node1" {:type :text :props {}}}
                      :children-by-parent {:doc ["node1" "missing"]} ; missing refers to non-existent node
                      :roots #{:doc :trash}
                      :derived {}}
          result (db/validate invalid-db)]
      (is (not (:ok? result)))
      (is (seq (:errors result))))))

(deftest interpret-test
  (testing "Successful transaction interpretation"
    (let [db (db/empty-db)
          ops [{:op :create-node :id "node1" :type :text :props {:content "Hello"}}
               {:op :place :id "node1" :under :doc :at :last}
               {:op :update-node :id "node1" :props {:content "World"}}]
          result (interp/interpret db ops)]

      (is (empty? (:issues result)))
      (is (= 3 (count (:trace result))))
      (is (contains? (get-in result [:db :nodes]) "node1"))
      (is (= "World" (get-in result [:db :nodes "node1" :props :content])))
      (is (= ["node1"] (get-in result [:db :children-by-parent :doc])))))

  (testing "Transaction with validation issues"
    (let [db (db/empty-db)
          ops [{:op :place :id "missing" :under :doc :at :last}] ; node doesn't exist
          result (interp/interpret db ops)]

      (is (seq (:issues result)))
      (is (= :node-not-found (get-in result [:issues 0 :issue])))
      (is (= db (:db result))))) ; database unchanged due to issue

  (testing "Cycle detection prevents invalid placement"
    (let [db (-> (db/empty-db)
                 (ops/create-node "parent" :container {})
                 (ops/create-node "child" :text {})
                 (ops/place "parent" :doc :last)
                 (ops/place "child" "parent" :last)
                 (db/derive-indexes))
          ops [{:op :place :id "parent" :under "child" :at :last}] ; would create cycle
          result (interp/interpret db ops)]

      (is (seq (:issues result)))
      (is (= :cycle-detected (get-in result [:issues 0 :issue])))))

  (testing "Duplicate create issues warning but doesn't change db"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {:content "Original"})
                 (db/derive-indexes))
          ops [{:op :create-node :id "node1" :type :text :props {:content "New"}}]
          result (interp/interpret db ops)]

      (is (seq (:issues result)))
      (is (= :duplicate-create (get-in result [:issues 0 :issue])))
      (is (= "Original" (get-in result [:db :nodes "node1" :props :content]))))))

(deftest normalization-test
  (testing "Adjacent update operations are merged"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {:a 1})
                 (db/derive-indexes))
          ops [{:op :update-node :id "node1" :props {:b 2}}
               {:op :update-node :id "node1" :props {:c 3}}]
          result (interp/interpret db ops)]

      (is (empty? (:issues result)))
      (is (= 1 (count (:trace result)))) ; merged into single operation
      (is (= {:a 1 :b 2 :c 3} (get-in result [:db :nodes "node1" :props])))))

  (testing "No-op place operations are dropped"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {})
                 (ops/place "node1" :doc 0)
                 (db/derive-indexes))
          ops [{:op :place :id "node1" :under :doc :at 0}] ; same position
          result (interp/interpret db ops)]

      (is (empty? (:issues result)))
      (is (empty? (:trace result))))) ; no-op was dropped

  (testing "Anchor validation with siblings"
    (let [db (-> (db/empty-db)
                 (ops/create-node "node1" :text {})
                 (ops/create-node "node2" :text {})
                 (ops/create-node "node3" :text {})
                 (ops/place "node1" :doc :last)
                 (ops/place "node2" :doc :last)
                 (db/derive-indexes))
          ops [{:op :place :id "node3" :under :doc :at {:before "missing"}}] ; invalid anchor
          result (interp/interpret db ops)]

      (is (seq (:issues result)))
      (is (= :anchor-not-sibling (get-in result [:issues 0 :issue]))))))