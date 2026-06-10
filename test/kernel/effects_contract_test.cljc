(ns kernel.effects-contract-test
  "Claim-level tests for the :effects channel in the dispatch contract.

   Effects are declarative side-effect requests — [[effect-kw arg-map] ...] —
   returned by pure handlers and executed only by the shell. The contract:
   - effects pass through dispatch untouched on success
   - effects are nil when the transaction fails validation (same guard as
     :session-updates — a failed transaction commits nothing, anywhere)
   - effects are nil when the state machine blocks the intent
   - malformed :effects shapes are rejected at apply-intent"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.intent-fixtures :as intent-fixtures]
            [kernel.api :as api]
            [kernel.db :as db]
            [kernel.intent :as intent]))

(def ^:private test-effect [[:test/ping {:n 1}]])

(use-fixtures :once
  (intent-fixtures/with-registered-intents
    {:fx-ok        {:handler (fn [_db _session _intent]
                               {:ops [{:op :create-node :id "x" :type :block :props {:text "X"}}
                                      {:op :place :id "x" :under :doc :at :last}]
                                :effects test-effect})}
     :fx-bad-ops   {:handler (fn [_db _session _intent]
                               ;; place of a nonexistent node → validation issue
                               {:ops [{:op :place :id "ghost" :under :doc :at :last}]
                                :effects test-effect})}
     :fx-malformed {:handler (fn [_db _session _intent]
                               {:effects {:not "a vector of tuples"}})}
     :fx-overlong  {:handler (fn [_db _session _intent]
                               {:effects [[:test/ping {:n 1} :extra-element]]})}
     :fx-gated     {:allowed-states #{:editing}
                    :handler (fn [_db _session _intent]
                               {:effects test-effect})}}))

(def ^:private background-session
  {:selection {:nodes #{} :focus nil :anchor nil}
   :ui {}})

(deftest effects-pass-through-on-success
  (testing "handler effects survive dispatch untouched when the tx applies"
    (let [{:keys [effects issues]} (api/dispatch (db/empty-db) nil {:type :fx-ok})]
      (is (empty? issues))
      (is (= test-effect effects)))))

(deftest effects-nil-on-validation-failure
  (testing "a failed transaction returns NO effects — same guard as session-updates"
    (let [{:keys [effects issues session-updates]}
          (api/dispatch (db/empty-db) nil {:type :fx-bad-ops})]
      (is (seq issues) "the bad op produced validation issues")
      (is (nil? effects) "effects suppressed on failure")
      (is (nil? session-updates) "session-updates suppressed on failure"))))

(deftest effects-nil-when-state-machine-blocks
  (testing "a state-machine-blocked intent returns no effects"
    (let [{:keys [effects issues ops]}
          (api/dispatch (db/empty-db) background-session {:type :fx-gated})]
      (is (empty? issues) "blocked intents are silent no-ops, not errors")
      (is (empty? ops))
      (is (nil? effects)))))

(deftest apply-intent-rejects-malformed-effects
  (testing "an :effects value that is not a vector of [kw map] tuples throws"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"vector of \[effect-kw arg-map\] tuples"
         (intent/apply-intent (db/empty-db) nil {:type :fx-malformed})))))

(deftest apply-intent-rejects-overlong-effect-tuples
  (testing "a 3-element effect tuple is rejected — the contract is [kw arg-map], exactly"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"vector of \[effect-kw arg-map\] tuples"
         (intent/apply-intent (db/empty-db) nil {:type :fx-overlong})))))
