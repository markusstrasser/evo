(ns algebra.anchor-test
  (:require [clojure.test :refer [deftest is testing]]
            [algebra.anchor :as anchor]))

(def sample-db
  {:children-by-parent {"P" ["A" "B" "C" "D"]}})

(deftest test-at-start
  (testing ":at-start resolves to index 0"
    (is (= {:idx 0 :normalized-anchor :first}
           (anchor/->index sample-db "P" :at-start)))))

(deftest test-at-end
  (testing ":at-end resolves to count of children"
    (is (= {:idx 4 :normalized-anchor :last}
           (anchor/->index sample-db "P" :at-end))))

  (testing ":at-end on empty parent"
    (is (= {:idx 0 :normalized-anchor :last}
           (anchor/->index {:children-by-parent {}} "Q" :at-end)))))

(deftest test-first-last-direct
  (testing ":first resolves to index 0"
    (is (= {:idx 0 :normalized-anchor :first}
           (anchor/->index sample-db "P" :first))))

  (testing ":last resolves to count of children"
    (is (= {:idx 4 :normalized-anchor :last}
           (anchor/->index sample-db "P" :last)))))

(deftest test-before-anchor
  (testing "{:before id} resolves to index of id"
    (is (= {:idx 1 :normalized-anchor {:before "B"}}
           (anchor/->index sample-db "P" {:before "B"}))))

  (testing "{:before id} at first position"
    (is (= {:idx 0 :normalized-anchor {:before "A"}}
           (anchor/->index sample-db "P" {:before "A"}))))

  (testing "{:before id} throws on unknown id"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"unknown sibling"
                         (anchor/->index sample-db "P" {:before "X"})))))

(deftest test-after-anchor
  (testing "{:after id} resolves to index after id"
    (is (= {:idx 2 :normalized-anchor {:after "B"}}
           (anchor/->index sample-db "P" {:after "B"}))))

  (testing "{:after id} at last position"
    (is (= {:idx 4 :normalized-anchor {:after "D"}}
           (anchor/->index sample-db "P" {:after "D"}))))

  (testing "{:after id} throws on unknown id"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"unknown sibling"
                         (anchor/->index sample-db "P" {:after "X"})))))

(deftest test-at-index-anchor
  (testing "{:at-index i} resolves to i"
    (is (= {:idx 2 :normalized-anchor {:at-index 2}}
           (anchor/->index sample-db "P" {:at-index 2}))))

  (testing "{:at-index 0} at start"
    (is (= {:idx 0 :normalized-anchor {:at-index 0}}
           (anchor/->index sample-db "P" {:at-index 0}))))

  (testing "{:at-index n} at end"
    (is (= {:idx 4 :normalized-anchor {:at-index 4}}
           (anchor/->index sample-db "P" {:at-index 4}))))

  (testing "{:at-index i} throws on negative"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"out of bounds"
                         (anchor/->index sample-db "P" {:at-index -1}))))

  (testing "{:at-index i} throws on > n"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"out of bounds"
                         (anchor/->index sample-db "P" {:at-index 5})))))

(deftest test-integer-anchor
  (testing "integer anchor resolves as :at-index"
    (is (= {:idx 2 :normalized-anchor {:at-index 2}}
           (anchor/->index sample-db "P" 2))))

  (testing "integer 0 resolves to start"
    (is (= {:idx 0 :normalized-anchor {:at-index 0}}
           (anchor/->index sample-db "P" 0))))

  (testing "integer throws on OOB"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                         #"out of bounds"
                         (anchor/->index sample-db "P" 5)))))

(deftest test-error-reasons
  (testing "Errors include machine-parsable :reason"
    (is (= ::anchor/missing-target
           (try
             (anchor/->index sample-db "P" {:before "X"})
             (catch #?(:clj Exception :cljs js/Error) e
               (:reason (ex-data e))))))

    (is (= ::anchor/oob
           (try
             (anchor/->index sample-db "P" {:at-index 10})
             (catch #?(:clj Exception :cljs js/Error) e
               (:reason (ex-data e))))))

    (is (= ::anchor/bad-anchor
           (try
             (anchor/->index sample-db "P" {:unknown "key"})
             (catch #?(:clj Exception :cljs js/Error) e
               (:reason (ex-data e))))))))

(deftest test-error-suggestions
  (testing "Errors include :suggest with recovery hint"
    (let [error-data (try
                       (anchor/->index sample-db "P" {:before "X"})
                       (catch #?(:clj Exception :cljs js/Error) e
                         (ex-data e)))]
      (is (= :at-end (get-in error-data [:suggest :replace-anchor]))))))

(deftest test-normalize-intent
  (testing "Normalizes {:into parent} to {:parent parent :anchor :at-end}"
    (is (= {:parent "P" :anchor :at-end}
           (anchor/normalize-intent {:into "P"}))))

  (testing "Preserves explicit :anchor"
    (is (= {:parent "P" :anchor {:after "A"}}
           (anchor/normalize-intent {:into "P" :anchor {:after "A"}}))))

  (testing "Leaves non-:into intents unchanged"
    (is (= {:parent "P" :anchor :at-start}
           (anchor/normalize-intent {:parent "P" :anchor :at-start})))))

(deftest test-resolve-anchor-helper
  (testing "resolve-anchor returns just the index"
    (is (= 2 (anchor/resolve-anchor sample-db "P" {:after "B"})))
    (is (= 0 (anchor/resolve-anchor sample-db "P" :at-start)))
    (is (= 4 (anchor/resolve-anchor sample-db "P" :at-end)))
    (is (= 0 (anchor/resolve-anchor sample-db "P" :first)))
    (is (= 4 (anchor/resolve-anchor sample-db "P" :last)))))
