(ns kernel.intent-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.intent :as intent]))

(deftest handler-result-contract-is-single-shape
  (testing "handlers return nil or a result map, not raw ops vectors"
    (intent/register-intent! ::raw-vector-result
                             {:doc "Test-only invalid handler result."
                              :handler (fn [_db _session _intent]
                                         [{:op :update-node
                                           :id "a"
                                           :props {:text "bad"}}])})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         #"Intent handler must return nil or a result map"
         (intent/apply-intent {} {} {:type ::raw-vector-result}))))

  (testing "nil means no-op while pending buffer still materializes"
    (intent/register-intent! ::nil-result
                             {:doc "Test-only no-op handler."
                              :handler (fn [_db _session _intent] nil)})
    (is (= [{:op :update-node :id "a" :props {:text "typed"}}]
           (:ops (intent/apply-intent
                  {}
                  {}
                  {:type ::nil-result
                   :pending-buffer {:block-id "a" :text "typed"}}))))))

(deftest unknown-intents-are-loud
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
       #"Unknown intent type"
       (intent/apply-intent {} {} {:type ::missing-intent}))))
