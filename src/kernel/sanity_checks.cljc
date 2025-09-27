(ns evolver.sanity-checks
  "REPL-friendly sanity checks for evolver patches.
   
   Usage in REPL:
     (require '[evolver.sanity-checks :as check])
     (check/run-all)              ; Run all checks
     (check/full-derivation)      ; Test specific patch
     (check/no-op-guard)          ; etc.
   
   Each function returns {:passed? boolean :details map}"
  (:require [evolver.core :as core]
            [evolver.schemas :as S]
            [evolver.invariants :as inv]))

;; ------------------------------------------------------------
;; Test utilities
;; ------------------------------------------------------------

(defn test-safely
  "Execute test function and return pass/fail with details."
  [test-name test-fn expected-behavior]
  (try
    (let [result (test-fn)]
      {:test test-name
       :passed? true
       :result result
       :expected expected-behavior})
    (catch Exception e
      {:test test-name
       :passed? false
       :error (.getMessage e)
       :expected expected-behavior})))

(defn test-throws
  "Execute test function expecting it to throw, return pass if it does."
  [test-name test-fn expected-error-substring]
  (try
    (let [result (test-fn)]
      {:test test-name
       :passed? false
       :result result
       :expected (str "Should throw error containing: " expected-error-substring)
       :problem "Expected exception but got result"})
    (catch Exception e
      (let [msg (.getMessage e)
            contains-expected? (.contains msg expected-error-substring)]
        {:test test-name
         :passed? contains-expected?
         :error msg
         :expected (str "Error containing: " expected-error-substring)
         :contains-expected? contains-expected?}))))

;; ------------------------------------------------------------
;; Patch 1: Full derivation by default
;; ------------------------------------------------------------

(defn full-derivation
  "Verify that *derive-pass* produces both Tier-A and Tier-B derived data."
  []
  (test-safely
   "Full derivation by default"
   (fn []
     (let [base-db {:nodes {"root" {:type :root :props {}}}
                    :children-by-parent-id {}}
           derived-db (core/*derive-pass* base-db)
           derived (:derived derived-db)

            ;; Check for key Tier-A fields
           tier-a-fields #{:parent-id-of :child-ids-of :pre :post :index-of}
           has-tier-a? (every? #(contains? derived %) tier-a-fields)

            ;; Check for key Tier-B fields  
           tier-b-fields #{:preorder :position-of :reachable-ids :orphan-ids :doc-index-of}
           has-tier-b? (every? #(contains? derived %) tier-b-fields)]

       {:has-tier-a? has-tier-a?
        :has-tier-b? has-tier-b?
        :all-keys (sort (keys derived))
        :success? (and has-tier-a? has-tier-b?)}))
   "Both Tier-A and Tier-B fields present in derived data"))

;; ------------------------------------------------------------
;; Patch 2: No-op guard in set-parent*
;; ------------------------------------------------------------

(defn no-op-guard
  "Verify that set-parent* returns identical object when no change needed."
  []
  (test-safely
   "No-op guard prevents unnecessary work"
   (fn []
     (let [base-db {:nodes {"root" {:type :root :props {}}
                            "child" {:type :div :props {}}}
                    :children-by-parent-id {"root" ["child"]}}

            ;; Direct call to set-parent* with same parent + nil pos
           result-db (core/set-parent* base-db {:id "child" :parent-id "root" :pos nil})
           direct-identical? (identical? base-db result-db)

            ;; Via interpret* - core data should be unchanged  
           derived-base (core/*derive-pass* base-db)
           interpreted-result (core/interpret* derived-base {:op :set-parent :id "child" :parent-id "root"})

           base-adj (select-keys derived-base [:nodes :children-by-parent-id])
           result-adj (select-keys interpreted-result [:nodes :children-by-parent-id])
           interpret-unchanged? (= base-adj result-adj)]

       {:direct-identical? direct-identical?
        :interpret-unchanged? interpret-unchanged?
        :success? (and direct-identical? interpret-unchanged?)}))
   "set-parent* returns same object when parent unchanged and pos=nil"))

;; ------------------------------------------------------------  
;; Patch 3: Malli validation
;; ------------------------------------------------------------

(defn malli-validation
  "Verify that Malli validation catches invalid operations at boundaries."
  []
  (let [valid-db {:nodes {"root" {:type :root :props {}}} :children-by-parent-id {}}

        ;; Test 1: Invalid DB
        db-test (test-throws
                 "Invalid DB validation"
                 #(S/validate-db! {:invalid "db"})
                 "Schema validation failed")

        ;; Test 2: Invalid TX
        tx-test (test-throws
                 "Invalid TX validation"
                 #(S/validate-tx! "not-a-valid-tx")
                 "Schema validation failed")

        ;; Test 3: Invalid OP
        op-test (test-throws
                 "Invalid OP validation"
                 #(S/validate-op! {:op :ensure-node :id 123}) ; id should be string
                 "should be a string")

        ;; Test 4: Integration - interpret* catches invalid op
        interpret-test (test-throws
                        "interpret* validation integration"
                        #(core/interpret* valid-db {:op :ensure-node :id 999})
                        "Schema validation failed")]

    {:db-validation (:passed? db-test)
     :tx-validation (:passed? tx-test)
     :op-validation (:passed? op-test)
     :interpret-integration (:passed? interpret-test)
     :all-tests [db-test tx-test op-test interpret-test]
     :success? (every? :passed? [db-test tx-test op-test interpret-test])}))

;; ------------------------------------------------------------
;; Patch 4: Enhanced invariants  
;; ------------------------------------------------------------

(defn enhanced-invariants
  "Verify that the four new safety assertions catch structural violations."
  []
  (let [;; Test 1: ROOT must exist
        root-exists-test
        (test-throws
         "ROOT must exist"
         #(inv/check-invariants
           {:nodes {"n1" {:type :div :props {}}}
            :children-by-parent-id {}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "ROOT node must exist")

        ;; Test 2: ROOT cannot be a child
        root-not-child-test
        (test-throws
         "ROOT cannot be child"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}} "n1" {:type :div :props {}}}
            :children-by-parent-id {"n1" ["root"]}
            :derived {:parent-id-of {"root" "n1"} :child-ids-of {}}})
         "ROOT cannot be a child")

        ;; Test 3: Child must exist in nodes
        child-exists-test
        (test-throws
         "Child must exist in nodes"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}}}
            :children-by-parent-id {"root" ["nonexistent"]}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "Child id missing from :nodes")

        ;; Test 4: No self-parenting
        no-self-parent-test
        (test-throws
         "No self-parenting"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}} "n1" {:type :div :props {}}}
            :children-by-parent-id {}
            :derived {:parent-id-of {"n1" "n1"} :child-ids-of {}}})
         "Self-parenting detected")]

    {:root-exists (:passed? root-exists-test)
     :root-not-child (:passed? root-not-child-test)
     :child-exists (:passed? child-exists-test)
     :no-self-parent (:passed? no-self-parent-test)
     :all-tests [root-exists-test root-not-child-test child-exists-test no-self-parent-test]
     :success? (every? :passed? [root-exists-test root-not-child-test child-exists-test no-self-parent-test])}))

;; ------------------------------------------------------------
;; Integration test
;; ------------------------------------------------------------

(defn integration-test
  "Test that all patches work together in a realistic scenario."
  []
  (test-safely
   "All patches working together"
   (fn []
     (let [;; Start with valid base
           base {:nodes {"root" {:type :root :props {}}} :children-by-parent-id {}}

            ;; Build up a small tree with full derivation
           step1 (core/interpret* base {:op :ensure-node :id "parent" :type :div})
           step2 (core/interpret* step1 {:op :set-parent :id "parent" :parent-id "root"})
           step3 (core/interpret* step2 {:op :ensure-node :id "child" :type :span})
           final (core/interpret* step3 {:op :set-parent :id "child" :parent-id "parent"})

            ;; Verify full derivation
           has-full-derivation? (and (contains? (:derived final) :parent-id-of)
                                     (contains? (:derived final) :preorder))

            ;; Verify no-op guard (same parent, no pos change)
           before-adj (select-keys final [:nodes :children-by-parent-id])
           after-noop (core/interpret* final {:op :set-parent :id "child" :parent-id "parent"})
           after-adj (select-keys after-noop [:nodes :children-by-parent-id])
           no-op-works? (= before-adj after-adj)

            ;; Verify invariants pass on valid structure
           invariants-pass? (try (inv/check-invariants final) true (catch Exception e false))

            ;; Verify tree structure is correct
           child-ids (get-in final [:children-by-parent-id "parent"])
           tree-correct? (= child-ids ["child"])]

       {:full-derivation? has-full-derivation?
        :no-op-works? no-op-works?
        :invariants-pass? invariants-pass?
        :tree-correct? tree-correct?
        :final-tree (:children-by-parent-id final)
        :success? (and has-full-derivation? no-op-works? invariants-pass? tree-correct?)}))
   "All patches work together in realistic tree building"))

;; ------------------------------------------------------------
;; Main test runner
;; ------------------------------------------------------------

(defn run-all
  "Run all sanity checks and return summary."
  []
  (println "🧪 Running evolver patch sanity checks...\n")

  (let [patch1 (full-derivation)
        patch2 (no-op-guard)
        patch3 (malli-validation)
        patch4 (enhanced-invariants)
        integration (integration-test)

        all-passed? (every? :success? [patch1 patch2 patch3 patch4 integration])]

    (println (str "📋 Patch 1 - Full Derivation:  " (if (:success? patch1) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 2 - No-op Guard:      " (if (:success? patch2) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 3 - Malli Validation: " (if (:success? patch3) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 4 - Enhanced Checks:  " (if (:success? patch4) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Integration Test:           " (if (:success? integration) "✅ PASS" "❌ FAIL")))
    (println)
    (println (str "🎯 Overall: " (if all-passed? "✅ ALL PATCHES WORKING" "❌ SOME ISSUES FOUND")))

    {:summary {:all-passed? all-passed?
               :patch-1 (:success? patch1)
               :patch-2 (:success? patch2)
               :patch-3 (:success? patch3)
               :patch-4 (:success? patch4)
               :integration (:success? integration)}
     :details {:full-derivation patch1
               :no-op-guard patch2
               :malli-validation patch3
               :enhanced-invariants patch4
               :integration-test integration}}))

;; ------------------------------------------------------------
;; Quick REPL helpers
;; ------------------------------------------------------------

(defn quick-check
  "Quick one-liner check - returns true if all patches working."
  []
  (:all-passed? (:summary (run-all))))

(defn show-details
  "Show detailed results for debugging."
  []
  (:details (run-all)))

(comment
  ;; REPL usage examples:

  ;; Run all checks
  (run-all)

  ;; Quick pass/fail
  (quick-check)

  ;; Individual patch tests  
  (full-derivation)
  (no-op-guard)
  (malli-validation)
  (enhanced-invariants)

  ;; Debug details
  (show-details))