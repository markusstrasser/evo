(ns utils.session-patch-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [utils.session-patch :as session-patch]))

(deftest session-patch-contract
  (let [session {:selection {:nodes #{"a"} :focus "a"}
                 :ui {:folded #{"x"}
                      :cursor-position 3
                      :history ["a" "b"]
                      :nested {:keep true :clear "value"}}}
        patch {:selection {:nodes #{"b"}}
               :ui {:folded #{}
                    :cursor-position nil
                    :history ["c"]
                    :nested {:clear nil}}}
        result (session-patch/merge-patch session patch)]
    (testing "nested maps preserve siblings"
      (is (true? (get-in result [:ui :nested :keep])))
      (is (= "a" (get-in result [:selection :focus]))))
    (testing "sets replace rather than union"
      (is (= #{"b"} (get-in result [:selection :nodes])))
      (is (= #{} (get-in result [:ui :folded]))))
    (testing "nil clears and vectors replace"
      (is (nil? (get-in result [:ui :cursor-position])))
      (is (nil? (get-in result [:ui :nested :clear])))
      (is (= ["c"] (get-in result [:ui :history]))))))
