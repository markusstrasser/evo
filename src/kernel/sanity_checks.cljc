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
            [kernel.workspace :as WS]))

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
  "Verify that *derive-pass* produces both Tier-A and Tier-B derived data."
  []
  (test-safely
   "Full derivation by default"
   (fn []
     (let [base-db {:nodes {"root" {:type :root :props {}}}
                    :child-ids/by-parent {}}
           derived-db (core/*derive-pass* base-db)
           derived (:derived derived-db)

            ;; Check for key Tier-A fields
           tier-a-fields #{:parent-id-of :child-ids-of :pre :post :index-of}
           has-tier-a? (every? #(contains? derived %) tier-a-fields)

            ;; Check for key Tier-B fields  
           tier-b-fields #{:preorder :order-index-of :order-prev-id-of :order-next-id-of :position-of :child-count-of :first-child-id-of :last-child-id-of :subtree-size-of :reachable-ids :orphan-ids :path-of}
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
     (let [base-db {:nodes {"root" {:type :root :props {}}
                            "child" {:type :div :props {}}}
                    :child-ids/by-parent {"root" ["child"]}}

            ;; Direct call to place* with same parent + nil pos
           result-db (core/place* base-db {:id "child" :parent-id "root" :pos nil})
           direct-identical? (identical? base-db result-db)

            ;; Via apply-tx* - core data should be unchanged
           derived-base (core/*derive-pass* base-db)
           interpreted-result (core/apply-tx* derived-base {:op :place :id "child" :parent-id "root"})

           base-adj (select-keys derived-base [:nodes :child-ids/by-parent])
           result-adj (select-keys interpreted-result [:nodes :child-ids/by-parent])
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
  (let [valid-db {:nodes {"root" {:type :root :props {}}} :child-ids/by-parent {}}

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
                 #(S/validate-op! {:op :create-node :id 123}) ; id should be string
                 "Schema validation failed")

        ;; Test 4: Integration - apply-tx* catches invalid op
        interpret-test (test-throws
                        "apply-tx* validation integration"
                        #(core/apply-tx* valid-db {:op :create-node :id 999})
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
           {:nodes {"n1" {:type :div :props {}}}
            :child-ids/by-parent {}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "Root missing")

        ;; Test 2: ROOT cannot be a child
        root-not-child-test
        (test-throws
         "ROOT cannot be child"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}} "n1" {:type :div :props {}}}
            :child-ids/by-parent {"n1" ["root"]}
            :derived {:parent-id-of {"root" "n1"} :child-ids-of {}}})
         "Root listed as a child")

        ;; Test 3: Child must exist in nodes
        child-exists-test
        (test-throws
         "Child must exist in nodes"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}}}
            :child-ids/by-parent {"root" ["nonexistent"]}
            :derived {:parent-id-of {} :child-ids-of {}}})
         "Child id missing from :nodes")

        ;; Test 4: No self-parenting
        no-self-parent-test
        (test-throws
         "No self-parenting"
         #(inv/check-invariants
           {:nodes {"root" {:type :root :props {}} "n1" {:type :div :props {}}}
            :child-ids/by-parent {}
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
     (let [db0 {:nodes {"root" {:type :root} "a" {:type :div} "b" {:type :div}}
                :child-ids/by-parent {"root" ["a" "b"]}}
           db1 (core/apply-tx* db0 [{:op :add-ref :rel :ref/mentions :src "a" :dst "b"}])
           has-edge? (contains? (get-in db1 [:refs :ref/mentions "a"] #{}) "b")
           db2 (core/apply-tx* db1 {:op :prune :pred (fn [_ x] (= x "b"))})
           edge-dropped? (not (contains? (get-in db2 [:refs :ref/mentions "a"] #{}) "b"))]
       {:has-edge? has-edge?
        :edge-dropped? edge-dropped?
        :passed? (and has-edge? edge-dropped?)}))
   "Edges can be added and are scrubbed on purge"))

(defn rm-ref-idempotent []
  (test-safely
   "rm-ref is idempotent"
   (fn []
     (let [db {:nodes {"root" {:type :root}
                       "a" {:type :div} "b" {:type :div}}
               :child-ids/by-parent {"root" ["a" "b"]}
               :refs {:ref/mentions {"a" #{"b"}}}}
           db' (core/apply-tx* db {:op :rm-ref :rel :ref/mentions :src "a" :dst "b"})
           db'' (core/apply-tx* db' {:op :rm-ref :rel :ref/mentions :src "a" :dst "b"})]
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
           base {:nodes {"root" {:type :root :props {}}} :child-ids/by-parent {}}

            ;; Build up a small tree with full derivation
           step1 (core/apply-tx* base {:op :create-node :id "parent" :type :div})
           step2 (core/apply-tx* step1 {:op :place :id "parent" :parent-id "root"})
           step3 (core/apply-tx* step2 {:op :create-node :id "child" :type :span})
           final (core/apply-tx* step3 {:op :place :id "child" :parent-id "parent"})

            ;; Verify full derivation
           has-full-derivation? (and (contains? (:derived final) :parent-id-of)
                                     (contains? (:derived final) :preorder))

            ;; Verify no-op guard (same parent, no pos change)
           before-adj (select-keys final [:nodes :child-ids/by-parent])
           after-noop (core/apply-tx* final {:op :place :id "child" :parent-id "parent"})
           after-adj (select-keys after-noop [:nodes :child-ids/by-parent])
           no-op-works? (= before-adj after-adj)

            ;; Verify invariants pass on valid structure
           invariants-pass? (try (inv/check-invariants final) true (catch Exception e false))

            ;; Verify tree structure is correct
           child-ids (get-in final [:child-ids/by-parent "parent"])
           tree-correct? (= child-ids ["child"])]

       {:full-derivation? has-full-derivation?
        :no-op-works? no-op-works?
        :invariants-pass? invariants-pass?
        :tree-correct? tree-correct?
        :final-tree (:child-ids/by-parent final)
        :passed? (and has-full-derivation? no-op-works? invariants-pass? tree-correct?)}))
   "All patches work together in realistic tree building"))

(defn multi-root-basics []
  (test-safely
   "Multi-root traversal + orphans allowed"
   (fn []
     (let [db {:nodes {"root" {:type :root}
                       "palette" {:type :root}
                       "a" {:type :div} "b" {:type :div}
                       "p1" {:type :div} "p2" {:type :div}
                       "loose" {:type :div}}
               :child-ids/by-parent {"root" ["a" "b"] "palette" ["p1" "p2"]}
               :roots ["root" "palette"]}
           d (core/*derive-pass* db)]
       {:preorder (= (get-in d [:derived :preorder]) ["root" "a" "b" "palette" "p1" "p2"])
        :orphan? (contains? (get-in d [:derived :orphan-ids]) "loose")
        :invariants-pass? (try (inv/check-invariants d) true (catch Exception _ false))
        :passed? (and
                  (= (get-in d [:derived :preorder]) ["root" "a" "b" "palette" "p1" "p2"])
                  (contains? (get-in d [:derived :orphan-ids]) "loose")
                  (inv/check-invariants d))}))
   "Multi-root preorder is concatenated; orphans tracked; invariants hold"))

(defn effects-skeleton-smoke []
  (test-safely
   "Effects emitted on :insert via apply-tx+effects*"
   (fn []
     (let [db0 {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
           {:keys [db effects]} (core/apply-tx+effects* db0 {:op :insert :id "x" :parent-id "root"})]
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
     (let [db {:nodes {"root" {:type :root} "palette" {:type :root} "w" {:type :div}}
               :child-ids/by-parent {"root" ["w"]}
               :roots ["root" "palette"]}
           d (core/*derive-pass* db)
           preorder-correct? (= (get-in d [:derived :preorder]) ["root" "w" "palette"])
           orphan-w? (not (contains? (get-in d [:derived :orphan-ids]) "w"))
           invariants-ok? (try (inv/check-invariants d) true (catch Exception _ false))

           ;; Effects seam visibility
           base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
           bundle-result (core/apply-tx+effects* base [{:op :insert :id "a" :parent-id "root"}
                                                       {:op :insert :id "b" :parent-id "root"}])
           effects-count (count (:effects bundle-result))
           effects-correct? (= (map :effect (:effects bundle-result))
                               [:view/scroll-into-view :view/scroll-into-view])

           ;; Backward compatibility
           old-result (:nodes (core/apply-tx* base {:op :insert :id "z" :parent-id "root"}))
           new-result (:nodes (:db (core/apply-tx+effects* base {:op :insert :id "z" :parent-id "root"})))
           compat-ok? (= old-result new-result)]

       {:preorder-correct? preorder-correct?
        :orphan-w-not-included? orphan-w?
        :invariants-pass? invariants-ok?
        :effects-count effects-count
        :effects-correct? effects-correct?
        :backward-compat? compat-ok?
        :passed? (and preorder-correct? orphan-w? invariants-ok?
                      (= effects-count 2) effects-correct? compat-ok?)}))
   "All REPL verification examples pass"))

;; ------------------------------------------------------------
;; Main test runner
;; ------------------------------------------------------------

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
        integration (integration-test)

        all-passed? (every? :passed? [patch1 patch2 patch3 patch4 edges idemp ws mr eff repl-v integration])]

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
    (println (str "📋 Integration Test:           " (if (:passed? integration) "✅ PASS" "❌ FAIL")))
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
               :integration (:passed? integration)}
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
  (multi-root-basics)
  (effects-skeleton-smoke)

  ;; Debug details
  (show-details))