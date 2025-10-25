(ns core-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [core.schema :as schema]
            [core.db :as db]
            [core.ops :as ops]))

(deftest function-schema-guards-test
  (testing "create-node operation validates output"
    (let [db (db/empty-db)
          result (ops/create-node db "n1" :paragraph {:text "hello"})]
      (is (schema/valid-db? result))))

  (testing "place operation validates output"
    (let [db (-> (db/empty-db)
                 (ops/create-node "n1" :paragraph {})
                 (db/derive-indexes))
          result (-> db
                     (ops/place "n1" :doc :first)
                     (db/derive-indexes))]
      (is (schema/valid-db? result))))

  (testing "update-node operation validates output"
    (let [db (-> (db/empty-db)
                 (ops/create-node "n1" :paragraph {:text "old"}))
          result (ops/update-node db "n1" {:text "new"})]
      (is (schema/valid-db? result)))))

(deftest schema-registry-test
  (testing "compiled schemas available"
    (let [schemas (schema/describe-ops)]
      (is (contains? schemas :Op-Create))
      (is (contains? schemas :Op-Place))
      (is (contains? schemas :Op-Update))
      (is (contains? schemas :Op))
      (is (contains? schemas :Transaction))
      (is (contains? schemas :Db))))

  (testing "compiled schemas are functions"
    (let [schemas (schema/describe-ops)]
      (is (fn? (:Op schemas)))
      (is (fn? (:Db schemas))))))