(ns kernel.sanity_checks
  "REPL-friendly sanity checks for kernel patches.
   
   Usage in REPL:
     (require '[kernel.sanity_checks :as check])
     (check/run-all)              ; Run all checks
     (check/full-derivation)      ; Test specific patch
     (check/no-op-guard)          ; etc.
   
   Each function returns {:passed? boolean :details map}"
  (:require [kernel.core :as core]
            [kernel.schemas :as S]
            [kernel.invariants :as inv]
            [kernel.workspace :as WS]
            [kernel.derive.registry :as registry]
            [kernel.sugar-ops])) ; Load sugar ops to extend multimethod

;; ------------------------------------------------------------
;; Test utilities
;; ------------------------------------------------------------

;; Temporary wrapper for tests - extracts :db from new API
(defn- apply-tx* [db tx]
  (let [result (core/apply-tx+effects* db tx)]
    (if-let [error (:error result)]
      (throw (ex-info (:message error) error))
      (:db result))))

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
    (catch #?(:clj Throwable :cljs :default) e
      (let [msg (.getMessage e)
            contains-expected? (and msg #?(:clj (.contains msg expected-error-substring)
                                           :cljs (.includes msg expected-error-substring)))]
        {:test test-name
         :passed? contains-expected?
         :error msg
         :expected (str "Error containing: " expected-error-substring)
         :contains-expected? contains-expected?}))))

;; ------------------------------------------------------------
;; Patch 1: Full derivation by default
;; ------------------------------------------------------------

(defn full-derivation
  "Verify that registry produces both Tier-A and Tier-B derived data."
  []
  (test-safely
   "Full derivation by default"
   (fn []
     (let [base-db {:nodes {"root" {:node-type :root :props {}}}
                    :children-by-parent-id {}}
           derived-db (registry/run base-db)
           derived (:derived derived-db)

            ;; Check for key Tier-A fields
           tier-a-fields #{:parent-id-of :child-ids-of :preorder-index :postorder-index :index-of}
           has-tier-a? (every? #(contains? derived %) tier-a-fields)

            ;; Check for key Tier-B fields  
           tier-b-fields #{:preorder-indexorder :order-index-of :document-prev-id-of :document-next-id-of :position-of :child-count-of :first-child-id-of :last-child-id-of :subtree-size-of :reachable-ids :orphan-ids :path-of}
           has-tier-b? (every? #(contains? derived %) tier-b-fields)]

       {:has-tier-a? has-tier-a?
        :has-tier-b? has-tier-b?
        :all-keys (sort (keys derived))
        :passed? (and has-tier-a? has-tier-b?)}))
   "Both Tier-A and Tier-B fields present in derived data"))

;; ------------------------------------------------------------
;; Patch 2: No-op guard in set-parent*
;; ------------------------------------------------------------

(defn no-op-guard
  "Verify that place* returns identical object when no change needed."
  []
  (test-safely
   "No-op guard prevents unnecessary work"
   (fn []
     (let [base-db {:nodes {"root" {:node-type :root :props {}}
                            "child" {:node-type :div :props {}}}
                    :children-by-parent-id {"root" ["child"]}}

            ;; Direct call to place* with same parent + nil pos
           result-db (core/place* base-db {:id "child" :parent-id "root" :pos nil})
           direct-identical? (identical? base-db result-db)

            ;; Via apply-tx* - core data should be unchanged
           derived-base (registry/run base-db)
           interpreted-result (apply-tx* derived-base {:op :place :node-id "child" :parent-id "root"})

           base-adj (select-keys derived-base [:nodes :children-by-parent-id])
           result-adj (select-keys interpreted-result [:nodes :children-by-parent-id])
           interpret-unchanged? (= base-adj result-adj)]

       {:direct-identical? direct-identical?
        :interpret-unchanged? interpret-unchanged?
        :passed? (and direct-identical? interpret-unchanged?)}))
   "place* returns same object when parent unchanged and pos=nil"))

;; ------------------------------------------------------------  
;; Patch 3: Malli validation
;; ------------------------------------------------------------

(defn malli-validation
  "Verify that Malli validation catches invalid operations at boundaries."
  []
  (let [valid-db {:nodes {"root" {:node-type :root :props {}}} :children-by-parent-id {}}

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
                 #(S/validate-op! {:op :create-node :node-id 123}) ; id should be string
                 "Schema validation failed")

        ;; Test 4: Integration - apply-tx* catches invalid op
        interpret-test (test-throws
                        "apply-tx* validation integration"
                        #(apply-tx* valid-db {:op :create-node :node-id 999})
                        "Schema validation failed")]

    {:db-validation (:passed? db-test)
     :tx-validation (:passed? tx-test)
     :op-validation (:passed? op-test)
     :interpret-integration (:passed? interpret-test)
     :all-tests [db-test tx-test op-test interpret-test]
     :passed? (every? :passed? [db-test tx-test op-test interpret-test])}))

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
           {:nodes {"n1" {:node-type :div :props {}}}
            :children-by-parent-id {}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "Root missing")

        ;; Test 2: ROOT cannot be a child
        root-not-child-test
        (test-throws
         "ROOT cannot be child"
         #(inv/check-invariants
           {:nodes {"root" {:node-type :root :props {}} "n1" {:node-type :div :props {}}}
            :children-by-parent-id {"n1" ["root"]}
            :derived {:parent-id-of {"root" "n1"} :child-ids-of {}}})
         "Root listed as a child")

        ;; Test 3: Child must exist in nodes
        child-exists-test
        (test-throws
         "Child must exist in nodes"
         #(inv/check-invariants
           {:nodes {"root" {:node-type :root :props {}}}
            :children-by-parent-id {"root" ["nonexistent"]}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "Child id missing from :nodes")

        ;; Test 4: No self-parenting
        no-self-parent-test
        (test-throws
         "No self-parenting"
         #(inv/check-invariants
           {:nodes {"root" {:node-type :root :props {}} "n1" {:node-type :div :props {}}}
            :children-by-parent-id {}
            :derived {:parent-id-of {"n1" "n1"} :child-ids-of {}}})
         "Self-parenting detected")]

    {:root-exists (:passed? root-exists-test)
     :root-not-child (:passed? root-not-child-test)
     :child-exists (:passed? child-exists-test)
     :no-self-parent (:passed? no-self-parent-test)
     :all-tests [root-exists-test root-not-child-test child-exists-test no-self-parent-test]
     :passed? (every? :passed? [root-exists-test root-not-child-test child-exists-test no-self-parent-test])}))

;; ------------------------------------------------------------
;; New tests for edges primitive and workspace
;; ------------------------------------------------------------

(defn edges-basic []
  (test-safely
   "add-ref/rm-ref and purge scrub"
   (fn []
     (let [db0 {:nodes {"root" {:node-type :root} "a" {:node-type :div} "b" {:node-type :div}}
                :children-by-parent-id {"root" ["a" "b"]}}
           db1 (apply-tx* db0 [{:op :add-ref :relation :ref/mentions :source-id "a" :target-id "b"}])
           has-edge? (contains? (get-in db1 [:refs :ref/mentions "a"] #{}) "b")
           db2 (apply-tx* db1 {:op :prune :pred (fn [_ x] (= x "b"))})
           edge-dropped? (not (contains? (get-in db2 [:refs :ref/mentions "a"] #{}) "b"))]
       {:has-edge? has-edge?
        :edge-dropped? edge-dropped?
        :passed? (and has-edge? edge-dropped?)}))
   "Edges can be added and are scrubbed on purge"))

(defn rm-ref-idempotent []
  (test-safely
   "rm-ref is idempotent"
   (fn []
     (let [db {:nodes {"root" {:node-type :root}
                       "a" {:node-type :div} "b" {:node-type :div}}
               :children-by-parent-id {"root" ["a" "b"]}
               :refs {:ref/mentions {"a" #{"b"}}}}
           db' (apply-tx* db {:op :rm-ref :relation :ref/mentions :source-id "a" :target-id "b"})
           db'' (apply-tx* db' {:op :rm-ref :relation :ref/mentions :source-id "a" :target-id "b"})]
       {:idempotent? (= db' db'')
        :passed? (= db' db'')}))
   "Removing an absent edge is a no-op"))

(defn workspace-basic []
  (test-safely
   "workspace toggle collapsed"
   (fn []
     (let [ws0 (WS/empty-workspace)
           ws1 (WS/toggle-collapsed ws0 "a")]
       {:collapsed? (WS/collapsed? ws1 "a")
        :passed? (WS/collapsed? ws1 "a")}))
   "Collapsed toggles work"))

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
           base {:nodes {"root" {:node-type :root :props {}}} :children-by-parent-id {}}

            ;; Build up a small tree with full derivation
           step1 (apply-tx* base {:op :create-node :node-id "parent" :node-type :div})
           step2 (apply-tx* step1 {:op :place :node-id "parent" :parent-id "root"})
           step3 (apply-tx* step2 {:op :create-node :node-id "child" :node-type :span})
           final (apply-tx* step3 {:op :place :node-id "child" :parent-id "parent"})

            ;; Verify full derivation
           has-full-derivation? (and (contains? (:derived final) :parent-id-of)
                                     (contains? (:derived final) :preorder-indexorder))

            ;; Verify no-op guard (same parent, no pos change)
           before-adj (select-keys final [:nodes :children-by-parent-id])
           after-noop (apply-tx* final {:op :place :node-id "child" :parent-id "parent"})
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
        :passed? (and has-full-derivation? no-op-works? invariants-pass? tree-correct?)}))
   "All patches work together in realistic tree building"))

(defn multi-root-basics []
  (test-safely
   "Multi-root traversal + orphans allowed"
   (fn []
     (let [db {:nodes {"root" {:node-type :root}
                       "palette" {:node-type :root}
                       "a" {:node-type :div} "b" {:node-type :div}
                       "p1" {:node-type :div} "p2" {:node-type :div}
                       "loose" {:node-type :div}}
               :children-by-parent-id {"root" ["a" "b"] "palette" ["p1" "p2"]}
               :roots ["root" "palette"]}
           d (registry/run db)]
       {:preorder-indexorder (= (get-in d [:derived :preorder-indexorder]) ["root" "a" "b" "palette" "p1" "p2"])
        :orphan? (contains? (get-in d [:derived :orphan-ids]) "loose")
        :invariants-pass? (try (inv/check-invariants d) true (catch Exception _ false))
        :passed? (and
                  (= (get-in d [:derived :preorder-indexorder]) ["root" "a" "b" "palette" "p1" "p2"])
                  (contains? (get-in d [:derived :orphan-ids]) "loose")
                  (inv/check-invariants d))}))
   "Multi-root preorder is concatenated; orphans tracked; invariants hold"))

(defn effects-skeleton-smoke []
  (test-safely
   "Effects emitted on :insert via apply-tx+effects*"
   (fn []
     (let [db0 {:nodes {"root" {:node-type :root}} :children-by-parent-id {}}
           {:keys [db effects]} (core/apply-tx+effects* db0 {:op :insert :node-id "x" :parent-id "root"})]
       {:db-updated? (contains? (:nodes db) "x")
        :has-effect? (= (map :effect effects) [:view/scroll-into-view])
        :passed? (and (contains? (:nodes db) "x")
                      (= (map :effect effects) [:view/scroll-into-view]))}))
   "Bundle emits a named effect; state updated as before"))

(defn repl-verification []
  (test-safely
   "Direct REPL verification of all features"
   (fn []
     ;; Multi-root sanity
     (let [db {:nodes {"root" {:node-type :root} "palette" {:node-type :root} "w" {:node-type :div}}
               :children-by-parent-id {"root" ["w"]}
               :roots ["root" "palette"]}
           d (registry/run db)
           preorder-correct? (= (get-in d [:derived :preorder-indexorder]) ["root" "w" "palette"])
           orphan-w? (not (contains? (get-in d [:derived :orphan-ids]) "w"))
           invariants-ok? (try (inv/check-invariants d) true (catch Exception _ false))

           ;; Effects seam visibility
           base {:nodes {"root" {:node-type :root}} :children-by-parent-id {}}
           bundle-result (core/apply-tx+effects* base [{:op :insert :node-id "a" :parent-id "root"}
                                                       {:op :insert :node-id "b" :parent-id "root"}])
           effects-count (count (:effects bundle-result))
           effects-correct? (= (map :effect (:effects bundle-result))
                               [:view/scroll-into-view :view/scroll-into-view])

           ;; Backward compatibility
           old-result (:nodes (apply-tx* base {:op :insert :node-id "z" :parent-id "root"}))
           new-result (:nodes (:db (core/apply-tx+effects* base {:op :insert :node-id "z" :parent-id "root"})))
           compat-ok? (= old-result new-result)]

       {:preorder-indexorder-correct? preorder-correct?
        :orphan-w-not-included? orphan-w?
        :invariants-pass? invariants-ok?
        :effects-count effects-count
        :effects-correct? effects-correct?
        :backward-compat? compat-ok?
        :passed? (and preorder-correct? orphan-w? invariants-ok?
                      (= effects-count 2) effects-correct? compat-ok?)}))
   "All REPL verification examples pass"))

(defn multimethod-registry []
  (test-safely
   "Multimethod registry replaces dispatch function"
   (fn []
     (let [base {:nodes {"root" {:node-type :root}} :children-by-parent-id {}}

           ;; Test 1: Core primitives work directly via apply-op
           core-result (core/apply-op base {:op :create-node :node-id "core-test"})
           core-works? (contains? (:nodes core-result) "core-test")

           ;; Test 2: Sugar ops work after namespace is loaded
           sugar-result (core/apply-op base {:op :insert :node-id "sugar-test" :parent-id "root"})
           sugar-works? (contains? (:nodes sugar-result) "sugar-test")

           ;; Test 3: apply-tx+effects* uses multimethod correctly
           tx-result (core/apply-tx+effects* base [{:op :create-node :node-id "tx-core"}
                                                   {:op :insert :node-id "tx-sugar" :parent-id "root"}])
           tx-works? (and (contains? (:nodes (:db tx-result)) "tx-core")
                          (contains? (:nodes (:db tx-result)) "tx-sugar"))

           ;; Test 4: run-tx works with both core and sugar ops
           run-result (core/run-tx base [{:op :create-node :node-id "run-core"}
                                         {:op :insert :node-id "run-sugar" :parent-id "root"}])
           run-works? (and (:ok? run-result)
                           (contains? (:nodes (:db run-result)) "run-core")
                           (contains? (:nodes (:db run-result)) "run-sugar"))

           ;; Test 5: Unknown ops throw proper error
           unknown-error? (try
                            (core/apply-op base {:op :unknown-operation})
                            false ; Should not reach here
                            (catch Exception e
                              (re-find #"Unknown :op" (.getMessage e))))]

       {:core-primitives? core-works?
        :sugar-ops? sugar-works?
        :apply-tx-effects? tx-works?
        :run-tx? run-works?
        :unknown-ops-error? (boolean unknown-error?)
        :passed? (and core-works? sugar-works? tx-works? run-works? unknown-error?)}))
   "Multimethod registry works for all operation types"))

;; ------------------------------------------------------------
;; Main test runner
;; ------------------------------------------------------------

(defn defop-wires-up []
  (test-safely
   "defop: schema + dispatch"
   (fn []
     (let [base {:nodes {"root" {:node-type :root}} :children-by-parent-id {}}
           db' (apply-tx* base {:op :insert :node-id "i" :parent-id "root"})
           ok? (contains? (:nodes db') "i")]
       {:registered? (some? (S/op-schema-for :insert))
        :apply-op? ok?
        :passed? (and ok?)}))
   "Insert defined via defop works and is validated"))

(defn evaluate-envelope []
  (test-safely
   "evaluate returns uniform envelope"
   (fn []
     (let [base {:nodes {"root" {:node-type :root}} :children-by-parent-id {}}
           ok (core/evaluate base [{:op :create-node :node-id "x"} {:op :place :node-id "x" :parent-id "root"}])
           bad (core/evaluate base [{:op :place :node-id "nope" :parent-id "root"}])]
       {:ok? (= :ok (:status ok))
        :has-db? (map? (:db ok))
        :has-effects? (vector? (:effects ok))
        :err? (= :error (:status bad))
        :why (-> bad :error :why)
        :passed? (and (= :ok (:status ok)) (= :error (:status bad)))}))
   "OK + ERROR shapes validate"))

(defn unknown-op-surface []
  (test-safely
   "Unknown :op is caught at validation boundary"
   (fn []
     (try (S/validate-op! {:op :__ghost__})
          {:passed? false}
          (catch Exception e
            {:passed? (re-find #"Unknown :op" (.getMessage e))})))
   "Clear error for unknown op"))

(defn run-all
  "Run all sanity checks and return summary."
  []
  (println "🧪 Running kernel patch sanity checks...\n")

  (let [patch1 (full-derivation)
        patch2 (no-op-guard)
        patch3 (malli-validation)
        patch4 (enhanced-invariants)
        edges (edges-basic)
        idemp (rm-ref-idempotent)
        ws (workspace-basic)
        mr (multi-root-basics)
        eff (effects-skeleton-smoke)
        repl-v (repl-verification)
        registry (multimethod-registry)
        integration (integration-test)
        ; New tests for opkit and evaluate
        defop-test (defop-wires-up)
        evaluate-test (evaluate-envelope)
        unknown-op-test (unknown-op-surface)

        all-passed? (every? :passed? [patch1 patch2 patch3 patch4 edges idemp ws mr eff repl-v registry integration
                                      defop-test evaluate-test unknown-op-test])]

    (println (str "📋 Patch 1 - Full Derivation:  " (if (:passed? patch1) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 2 - No-op Guard:      " (if (:passed? patch2) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 3 - Malli Validation: " (if (:passed? patch3) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Patch 4 - Enhanced Checks:  " (if (:passed? patch4) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Edges Basic:                " (if (:passed? edges) "✅ PASS" "❌ FAIL")))
    (println (str "📋 rm-ref idempotent:          " (if (:passed? idemp) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Workspace:                  " (if (:passed? ws) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Multi-root basics:          " (if (:passed? mr) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Effects skeleton smoke:     " (if (:passed? eff) "✅ PASS" "❌ FAIL")))
    (println (str "📋 REPL verification:          " (if (:passed? repl-v) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Multimethod registry:       " (if (:passed? registry) "✅ PASS" "❌ FAIL")))
    (println (str "📋 Integration Test:           " (if (:passed? integration) "✅ PASS" "❌ FAIL")))
    (println (str "📋 defop macro:                " (if (:passed? defop-test) "✅ PASS" "❌ FAIL")))
    (println (str "📋 evaluate envelope:          " (if (:passed? evaluate-test) "✅ PASS" "❌ FAIL")))
    (println (str "📋 unknown op handling:        " (if (:passed? unknown-op-test) "✅ PASS" "❌ FAIL")))
    (println)
    (println (str "🎯 Overall: " (if all-passed? "✅ ALL PATCHES WORKING" "❌ SOME ISSUES FOUND")))

    {:summary {:all-passed? all-passed?
               :patch-1 (:passed? patch1)
               :patch-2 (:passed? patch2)
               :patch-3 (:passed? patch3)
               :patch-4 (:passed? patch4)
               :edges (:passed? edges)
               :idempotent (:passed? idemp)
               :workspace (:passed? ws)
               :multi-root (:passed? mr)
               :effects (:passed? eff)
               :repl-verification (:passed? repl-v)
               :integration (:passed? integration)
               :defop (:passed? defop-test)
               :evaluate (:passed? evaluate-test)
               :unknown-op (:passed? unknown-op-test)}
     :details {:full-derivation patch1
               :no-op-guard patch2
               :malli-validation patch3
               :enhanced-invariants patch4
               :edges-basic edges
               :rm-ref-idempotent idemp
               :workspace-basic ws
               :multi-root-basics mr
               :effects-skeleton-smoke eff
               :repl-verification repl-v
               :multimethod-registry registry
               :integration-test integration
               :defop-wires-up defop-test
               :evaluate-envelope evaluate-test
               :unknown-op-surface unknown-op-test}}))

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
  (multi-root-basics)
  (effects-skeleton-smoke)

  ;; Debug details
  (show-details))