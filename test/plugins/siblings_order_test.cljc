(ns plugins.siblings-order-test
  "Tests for siblings-order plugin."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [plugins.siblings-order :as so]
            [plugins.registry :as reg]
            [core.db :as db]
            [core.permutation :as perm]))

(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (f)
    (reg/clear!)))

(deftest derived-child-order
  (testing "Plugin adds child-order-of to derived"
    (so/init!)
    (let [test-db {:children-by-parent {:doc [:a :b :c]
                                         :a [:a1 :a2]}}
          result (db/derive-indexes test-db)]
      (is (= {:doc [:a :b :c], :a [:a1 :a2]}
             (get-in result [:derived :siblings :child-order-of]))))))

(deftest target-permutation-computation
  (testing "Computes correct permutation for reordering"
    (let [db {:children-by-parent {:doc [:a :b :c]}}
          target [:b :c :a]
          p (so/target-permutation db :doc target)]
      (is (= (perm/arrange [:a :b :c] p) target))))

  (testing "Throws on invalid target"
    (let [db {:children-by-parent {:doc [:a :b :c]}}]
      (is (thrown? #?(:clj AssertionError :cljs js/Error)
                   (so/target-permutation db :doc [:a :b :d]))))))
