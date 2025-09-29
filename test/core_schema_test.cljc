(ns core-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [core.schema :as schema]
            [core.db :as db]
            [core.ops :as ops]
            [malli.core :as m]))

(deftest transformation-pipeline-test
  (testing "decode operation with transformer"
    (let [raw-op {:op "create-node" :id "n1" :type "paragraph" :props {:text "hello"}}
          decoded (schema/decode-op raw-op)]
      (is (schema/valid-op? decoded))))

  (testing "encode and decode database"
    (let [db (db/empty-db)
          encoded (schema/encode-db db)
          decoded (schema/decode-db encoded)]
      (is (schema/valid-db? decoded))
      (is (= db decoded)))))

(deftest generative-testing-test
  (testing "generate valid operations"
    (dotimes [_ 10]
      (let [op (schema/generate-op)]
        (is (schema/valid-op? op)))))

  (testing "generate valid create operations"
    (dotimes [_ 5]
      (let [op (schema/generate-create-op)]
        (is (schema/valid-op? op))
        (is (= :create-node (:op op))))))

  (testing "generate valid place operations"
    (dotimes [_ 5]
      (let [op (schema/generate-place-op)]
        (is (schema/valid-op? op))
        (is (= :place (:op op))))))

  (testing "generate valid update operations"
    (dotimes [_ 5]
      (let [op (schema/generate-update-op)]
        (is (schema/valid-op? op))
        (is (= :update-node (:op op))))))

  (testing "generate valid transactions"
    (dotimes [_ 5]
      (let [tx (schema/generate-transaction 3)]
        (is (m/validate schema/Transaction tx))
        (is (= 3 (count tx)))))))

(deftest schema-inference-test
  (testing "infer node schema from samples"
    (let [nodes [{:type :paragraph :props {:text "hello" :bold true}}
                 {:type :paragraph :props {:text "world" :bold false}}]
          inferred (schema/infer-node-schema nodes)]
      (is (some? inferred))
      (is (m/validate inferred (first nodes)))))

  (testing "infer operation schema"
    (is (= schema/Op-Create (schema/infer-op-schema {:op :create-node})))
    (is (= schema/Op-Place (schema/infer-op-schema {:op :place})))
    (is (= schema/Op-Update (schema/infer-op-schema {:op :update-node})))))

(deftest fast-validation-test
  (testing "fast operation validation"
    (let [valid-op {:op :create-node :id "n1" :type :paragraph :props {}}
          invalid-op {:op :invalid}]
      (is (schema/validate-op-fast valid-op))
      (is (not (schema/validate-op-fast invalid-op)))))

  (testing "fast database validation"
    (let [valid-db (db/empty-db)]
      (is (schema/validate-db-fast valid-db))))

  (testing "fast transaction validation"
    (let [valid-tx [{:op :create-node :id "n1" :type :p :props {}}]
          invalid-tx [{:op :invalid}]]
      (is (schema/validate-transaction-fast valid-tx))
      (is (not (schema/validate-transaction-fast invalid-tx))))))

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

(deftest human-readable-errors-test
  (testing "explain human readable errors"
    (let [invalid-op {:op :invalid :id 123}
          explanation (schema/explain-human schema/Op invalid-op)]
      (is (map? explanation))
      (is (contains? explanation :op)))))

(deftest transformation-composability-test
  (testing "api transformer strips extra keys"
    (let [op-with-extra {:op :create-node :id "n1" :type :p :props {} :extra "data"}
          decoded (schema/decode-op op-with-extra)]
      (is (not (contains? decoded :extra)))))

  (testing "strict transformer"
    (let [db (db/empty-db)]
      (is (schema/valid-db? (m/decode schema/Db db schema/strict-transformer))))))

(deftest property-based-validation-test
  (testing "generated operations always valid"
    (dotimes [_ 20]
      (let [op (schema/generate-op)]
        (is (schema/validate-op-fast op))
        (is (schema/valid-op? op)))))

  (testing "generated databases always valid"
    (dotimes [_ 10]
      (let [db (schema/generate-db)]
        (is (schema/validate-db-fast db)))))

  (testing "decode preserves validity"
    (dotimes [_ 10]
      (let [op (schema/generate-op)
            decoded (schema/decode-op op)]
        (is (schema/valid-op? decoded))))))