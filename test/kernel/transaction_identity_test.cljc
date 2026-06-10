(ns kernel.transaction-identity-test
  "Claim-level tests: interpret PRESERVES db identity when nothing applies.

   The executor's skip-reset guard and the log refold rely on this: a
   dispatch that changes nothing must return the very same db object so
   identical?/= short-circuit and no re-render/save/derive work runs.
   Before this guarantee, every dispatch — including pure-session intents
   and failed validation — re-derived all indexes (full backlinks re-parse)
   and defeated the executor's reset guard."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]))

(defn- seeded-db
  "Derived db with two blocks under :doc — [a b]."
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "A"}}
                     {:op :place :id "a" :under :doc :at :last}
                     {:op :create-node :id "b" :type :block :props {:text "B"}}
                     {:op :place :id "b" :under :doc :at :last}]
                    {:tx/now-ms 1})
      :db))

(deftest interpret-preserves-identity-on-empty-ops
  (testing "zero ops in → the SAME db object out (not merely equal)"
    (let [d (seeded-db)
          result (tx/interpret d [])]
      (is (identical? d (:db result)))
      (is (empty? (:issues result))))))

(deftest interpret-preserves-identity-on-noop-place
  (testing "placing a block at its current position normalizes away → identical db"
    (let [d (seeded-db)
          result (tx/interpret d [{:op :place :id "b" :under :doc :at :last}])]
      (is (identical? d (:db result)) "no-op place returns the input db object")
      (is (empty? (:ops result)) "the op normalized away")
      (is (empty? (:issues result)))))
  (testing "negative case: a REAL place returns a new, changed db"
    (let [d (seeded-db)
          result (tx/interpret d [{:op :place :id "b" :under :doc :at :first}])]
      (is (not (identical? d (:db result))))
      (is (= ["b" "a"] (get-in (:db result) [:children-by-parent :doc])))
      (is (seq (:ops result))))))

(deftest interpret-preserves-identity-on-validation-failure
  (testing "failed validation returns the input db object — no partial apply, no re-derive"
    (let [d (seeded-db)
          result (tx/interpret d [{:op :place :id "missing" :under :doc :at :last}])]
      (is (identical? d (:db result)))
      (is (seq (:issues result))))))

(deftest interpret-still-derives-when-ops-apply
  (testing "real ops still produce a fresh, fully-derived db"
    (let [d (seeded-db)
          result (tx/interpret d [{:op :create-node :id "c" :type :block :props {:text "C"}}
                                  {:op :place :id "c" :under :doc :at :last}]
                               {:tx/now-ms 2})
          d' (:db result)]
      (is (= ["a" "b" "c"] (get-in d' [:children-by-parent :doc])))
      (is (= :doc (get-in d' [:derived :parent-of "c"])) "derived indexes recomputed")
      (is (:ok? (db/validate d')) "result validates incl. derived freshness"))))
