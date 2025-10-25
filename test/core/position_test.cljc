(ns core.position-test
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.position :as pos]))

(def sample-db
  {:children-by-parent {"P" ["A" "B" "C" "D"]}})

(deftest test-at-start
  (testing ":at-start resolves to index 0"
    (is (= {:idx 0 :normalized-anchor :first}
           (pos/->index sample-db "P" :at-start)))))

(deftest test-at-end
  (testing ":at-end resolves to count of children"
    (is (= {:idx 4 :normalized-anchor :last}
           (pos/->index sample-db "P" :at-end))))

  (testing ":at-end on empty parent"
    (is (= {:idx 0 :normalized-anchor :last}
           (pos/->index {:children-by-parent {}} "Q" :at-end)))))

(deftest test-first-last-direct
  (testing ":first resolves to index 0"
    (is (= {:idx 0 :normalized-anchor :first}
           (pos/->index sample-db "P" :first))))

  (testing ":last resolves to count of children"
    (is (= {:idx 4 :normalized-anchor :last}
           (pos/->index sample-db "P" :last)))))

(deftest test-before-anchor
  (testing "{:before id} resolves to index of id"
    (is (= {:idx 1 :normalized-anchor {:before "B"}}
           (pos/->index sample-db "P" {:before "B"}))))

  (testing "{:before id} at first position"
    (is (= {:idx 0 :normalized-anchor {:before "A"}}
           (pos/->index sample-db "P" {:before "A"}))))

  (testing "{:before id} throws on unknown id"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"unknown sibling"
                         (pos/->index sample-db "P" {:before "X"})))))

(deftest test-after-anchor
  (testing "{:after id} resolves to index after id"
    (is (= {:idx 2 :normalized-anchor {:after "B"}}
           (pos/->index sample-db "P" {:after "B"}))))

  (testing "{:after id} at last position"
    (is (= {:idx 4 :normalized-anchor {:after "D"}}
           (pos/->index sample-db "P" {:after "D"}))))

  (testing "{:after id} throws on unknown id"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"unknown sibling"
                         (pos/->index sample-db "P" {:after "X"})))))


(deftest test-error-reasons
  (testing "Errors include machine-parsable :reason"
    (is (= ::pos/missing-target
           (try
             (pos/->index sample-db "P" {:before "X"})
             (catch #?(:clj Exception :cljs js/Error) e
               (:reason (ex-data e))))))

    (is (= ::pos/bad-anchor
           (try
             (pos/->index sample-db "P" {:unknown "key"})
             (catch #?(:clj Exception :cljs js/Error) e
               (:reason (ex-data e))))))))

(deftest test-error-suggestions
  (testing "Errors include :suggest with recovery hint"
    (let [error-data (try
                       (pos/->index sample-db "P" {:before "X"})
                       (catch #?(:clj Exception :cljs js/Error) e
                         (ex-data e)))]
      (is (= :at-end (get-in error-data [:suggest :replace-anchor]))))))

(deftest test-normalize-intent
  (testing "Normalizes {:into parent} to {:parent parent :anchor :at-end}"
    (is (= {:parent "P" :anchor :at-end}
           (pos/normalize-intent {:into "P"}))))

  (testing "Preserves explicit :anchor"
    (is (= {:parent "P" :anchor {:after "A"}}
           (pos/normalize-intent {:into "P" :anchor {:after "A"}}))))

  (testing "Leaves non-:into intents unchanged"
    (is (= {:parent "P" :anchor :at-start}
           (pos/normalize-intent {:parent "P" :anchor :at-start})))))

(deftest test-resolve-anchor-helper
  (testing "resolve-anchor returns just the index"
    (is (= 2 (pos/resolve-anchor sample-db "P" {:after "B"})))
    (is (= 0 (pos/resolve-anchor sample-db "P" :at-start)))
    (is (= 4 (pos/resolve-anchor sample-db "P" :at-end)))
    (is (= 0 (pos/resolve-anchor sample-db "P" :first)))
    (is (= 4 (pos/resolve-anchor sample-db "P" :last)))))
