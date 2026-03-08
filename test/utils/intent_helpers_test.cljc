(ns utils.intent-helpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [utils.intent-helpers :as helpers]))

(deftest merge-session-updates-recursively-merges-nested-maps
  (testing "nested map fragments under the same top-level section are preserved"
    (is (= {:ui {:editing-block-id "b"
                 :cursor-memory {:line-pos 3
                                 :direction :up}}}
           (helpers/merge-session-updates
            {:ui {:editing-block-id "b"
                  :cursor-memory {:line-pos 3}}}
            {:ui {:cursor-memory {:direction :up}}})))))

(deftest merge-session-updates-keeps-last-leaf-value
  (testing "non-map leaves stay last-write-wins so computed sets are not unioned"
    (is (= {:ui {:folded #{"b"}}}
           (helpers/merge-session-updates
            {:ui {:folded #{"a"}}}
            {:ui {:folded #{"b"}}})))))

(deftest merge-session-updates-still-composes-top-level-sections
  (testing "selection and ui fragments can be composed together"
    (is (= {:selection {:nodes #{"a"} :focus "a" :anchor "a" :direction nil}
            :ui {:editing-block-id "a" :cursor-position 4}}
           (helpers/merge-session-updates
            (helpers/select-only-update "a")
            (helpers/make-cursor-update "a" 4))))))
