(ns kernel.tx-normalize-test
  (:require [kernel.tx.normalize :as norm]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest test-cancel-create-then-delete
  (testing "cancel-create-then-delete removes matching create/delete pairs"
    ;; Basic cancellation
    (let [ops [{:op :create-node :id "temp" :type :div}
               {:op :delete :id "temp"}]
          result (norm/cancel-create-then-delete ops)]
      (is (= [] result)))

    ;; Cancellation with other operations
    (let [ops [{:op :create-node :id "temp" :type :div}
               {:op :create-node :id "keep" :type :span}
               {:op :delete :id "temp"}]
          result (norm/cancel-create-then-delete ops)]
      (is (= [{:op :create-node :id "keep" :type :span}] result)))

    ;; Multiple independent cancellations
    (let [ops [{:op :create-node :id "temp1" :type :div}
               {:op :create-node :id "temp2" :type :span}
               {:op :delete :id "temp1"}
               {:op :delete :id "temp2"}]
          result (norm/cancel-create-then-delete ops)]
      (is (= [] result)))

    ;; Create without delete - no cancellation
    (let [ops [{:op :create-node :id "keep" :type :div}]
          result (norm/cancel-create-then-delete ops)]
      (is (= ops result)))

    ;; Delete without create - no cancellation
    (let [ops [{:op :delete :id "missing"}]
          result (norm/cancel-create-then-delete ops)]
      (is (= ops result)))

    ;; Different IDs - no cancellation
    (let [ops [{:op :create-node :id "A" :type :div}
               {:op :delete :id "B"}]
          result (norm/cancel-create-then-delete ops)]
      (is (= ops result)))))

(deftest test-drop-noop-reorder
  (testing "drop-noop-reorder removes reorder ops with nil position"
    ;; Basic noop removal
    (let [ops [{:op :reorder :id "item" :pos nil}]
          result (norm/drop-noop-reorder ops)]
      (is (= [] result)))

    ;; Noop with other operations
    (let [ops [{:op :create-node :id "item" :type :div}
               {:op :reorder :id "item" :pos nil}
               {:op :update-node :id "item" :props {:class "test"}}]
          result (norm/drop-noop-reorder ops)]
      (is (= [{:op :create-node :id "item" :type :div}
              {:op :update-node :id "item" :props {:class "test"}}] result)))

    ;; Valid reorder - no removal
    (let [ops [{:op :reorder :id "item" :pos :first}]
          result (norm/drop-noop-reorder ops)]
      (is (= ops result)))

    ;; Mixed valid and invalid reorders
    (let [ops [{:op :reorder :id "item1" :pos nil}
               {:op :reorder :id "item2" :pos :first}
               {:op :reorder :id "item3" :pos nil}]
          result (norm/drop-noop-reorder ops)]
      (is (= [{:op :reorder :id "item2" :pos :first}] result)))))

(deftest test-merge-adjacent-patches
  (testing "merge-adjacent-patches combines consecutive update-node ops"
    ;; Basic merge
    (let [ops [{:op :update-node :id "item" :props {:a 1}}
               {:op :update-node :id "item" :props {:b 2}}]
          result (norm/merge-adjacent-patches ops)]
      (is (= [{:op :update-node :id "item" :props {:a 1 :b 2}}] result)))

    ;; Merge interrupted by different operation
    (let [ops [{:op :update-node :id "item" :props {:a 1}}
               {:op :create-node :id "other" :type :div}
               {:op :update-node :id "item" :props {:b 2}}]
          result (norm/merge-adjacent-patches ops)]
      (is (= [{:op :update-node :id "item" :props {:a 1}}
              {:op :create-node :id "other" :type :div}
              {:op :update-node :id "item" :props {:b 2}}] result)))

    ;; Different IDs - no merge
    (let [ops [{:op :update-node :id "item1" :props {:a 1}}
               {:op :update-node :id "item2" :props {:b 2}}]
          result (norm/merge-adjacent-patches ops)]
      (is (= ops result)))

    ;; Multiple consecutive merges
    (let [ops [{:op :update-node :id "item" :props {:a 1}}
               {:op :update-node :id "item" :props {:b 2}}
               {:op :update-node :id "item" :props {:c 3}}]
          result (norm/merge-adjacent-patches ops)]
      (is (= [{:op :update-node :id "item" :props {:a 1 :b 2 :c 3}}] result)))

    ;; Property overrides (later wins)
    (let [ops [{:op :update-node :id "item" :props {:a 1 :b 1}}
               {:op :update-node :id "item" :props {:b 2 :c 3}}]
          result (norm/merge-adjacent-patches ops)]
      (is (= [{:op :update-node :id "item" :props {:a 1 :b 2 :c 3}}] result)))))

(deftest test-normalize-integration
  (testing "normalize applies all rules and achieves fixed point"
    ;; Complex scenario combining all rules
    (let [ops [{:op :create-node :id "temp" :type :div}
               {:op :update-node :id "keep" :props {:a 1}}
               {:op :reorder :id "item" :pos nil}
               {:op :update-node :id "keep" :props {:b 2}}
               {:op :delete :id "temp"}
               {:op :create-node :id "final" :type :span}]
          result (norm/normalize ops)]
      (is (= [{:op :update-node :id "keep" :props {:a 1 :b 2}}
              {:op :create-node :id "final" :type :span}] result)))

    ;; Empty operations
    (let [result (norm/normalize [])]
      (is (= [] result)))

    ;; No optimizations needed
    (let [ops [{:op :create-node :id "item" :type :div}
               {:op :place :id "item" :parent-id "root"}]
          result (norm/normalize ops)]
      (is (= ops result)))))

(deftest test-normalize-idempotence
  (testing "normalize is idempotent"
    ;; Basic idempotence
    (let [ops [{:op :create-node :id "temp" :type :div}
               {:op :delete :id "temp"}
               {:op :reorder :id "item" :pos nil}]
          once (norm/normalize ops)
          twice (norm/normalize once)]
      (is (= once twice)))

    ;; Complex case idempotence
    (let [ops [{:op :update-node :id "item" :props {:a 1}}
               {:op :create-node :id "temp" :type :div}
               {:op :update-node :id "item" :props {:b 2}}
               {:op :delete :id "temp"}
               {:op :reorder :id "other" :pos nil}]
          once (norm/normalize ops)
          twice (norm/normalize once)
          thrice (norm/normalize twice)]
      (is (= once twice thrice)))

    ;; Empty operations idempotence
    (let [once (norm/normalize [])
          twice (norm/normalize once)]
      (is (= once twice)))))

(deftest test-normalize-preserves-semantics
  (testing "normalize preserves operation semantics"
    ;; Operations that should not be touched
    (let [untouchable-ops [{:op :create-node :id "item" :type :div}
                           {:op :place :id "item" :parent-id "root"}
                           {:op :move :id "item" :to-parent-id "other"}
                           {:op :insert :id "new" :parent-id "root"}]
          result (norm/normalize untouchable-ops)]
      (is (= untouchable-ops result)))

    ;; Ensure order is preserved for non-optimizable sequences
    (let [ops [{:op :create-node :id "A" :type :div}
               {:op :create-node :id "B" :type :span}
               {:op :create-node :id "C" :type :p}]
          result (norm/normalize ops)]
      (is (= ops result)))))

(deftest test-edge-cases
  (testing "edge cases and boundary conditions"
    ;; Single operation
    (let [ops [{:op :create-node :id "item" :type :div}]
          result (norm/normalize ops)]
      (is (= ops result)))

    ;; Operations without :id field (should not crash)
    (let [ops [{:op :custom-op :data "test"}]
          result (norm/normalize ops)]
      (is (= ops result)))

    ;; Operations with nil :id (should not crash)
    (let [ops [{:op :create-node :id nil :type :div}]
          result (norm/normalize ops)]
      (is (= ops result)))

    ;; Mixed operation types
    (let [ops [{:op :create-node :id "A" :type :div}
               {:op :add-ref :rel :mentions :src "A" :dst "B"}
               {:op :delete :id "A"}]
          result (norm/normalize ops)]
      ;; Should cancel create/delete but leave add-ref
      (is (= [{:op :add-ref :rel :mentions :src "A" :dst "B"}] result)))))

(deftest test-realistic-scenarios
  (testing "realistic operation sequences"
    ;; UI editing scenario: create, modify, delete
    (let [editing-ops [{:op :create-node :id "draft" :type :div}
                       {:op :update-node :id "draft" :props {:class "editing"}}
                       {:op :update-node :id "draft" :props {:text "Hello"}}
                       {:op :delete :id "draft"}]
          result (norm/normalize editing-ops)]
      (is (= [] result))) ; Entire sequence cancels out

    ;; Property refinement scenario
    (let [refinement-ops [{:op :create-node :id "item" :type :div}
                          {:op :update-node :id "item" :props {:width "100px"}}
                          {:op :update-node :id "item" :props {:height "200px"}}
                          {:op :update-node :id "item" :props {:color "red"}}]
          result (norm/normalize refinement-ops)]
      (is (= [{:op :create-node :id "item" :type :div}
              {:op :update-node :id "item" :props {:width "100px" :height "200px" :color "red"}}] result)))

    ;; Mixed optimizations scenario
    (let [mixed-ops [{:op :create-node :id "temp1" :type :div}
                     {:op :create-node :id "keep" :type :span}
                     {:op :update-node :id "keep" :props {:a 1}}
                     {:op :reorder :id "something" :pos nil}
                     {:op :update-node :id "keep" :props {:b 2}}
                     {:op :delete :id "temp1"}
                     {:op :create-node :id "temp2" :type :p}
                     {:op :delete :id "temp2"}]
          result (norm/normalize mixed-ops)]
      (is (= [{:op :create-node :id "keep" :type :span}
              {:op :update-node :id "keep" :props {:a 1 :b 2}}] result)))))

(comment
  ;; REPL test examples:

  ;; Run individual tests
  (test-cancel-create-then-delete)
  (test-normalize-idempotence)
  (test-realistic-scenarios)

  ;; Run all tests
  #?(:clj (clojure.test/run-tests)
     :cljs (cljs.test/run-tests)))