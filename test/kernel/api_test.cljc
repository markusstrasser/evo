(ns kernel.api-test
  "Tests for the dispatch seam — kernel.api/dispatch.

   Focus: the STATE-MACHINE GUARD branch of dispatch* (api.cljc), which silently
   no-ops intents that aren't allowed in the current interaction state. Before
   this file that branch had ZERO coverage: the state-machine unit tests assert
   the gating predicates (idle-guard, intent-allowed?) in isolation, but nothing
   asserted that dispatch actually drops a blocked intent at the seam the app
   uses. Testing a predicate in isolation rather than at its consumed seam is
   exactly how the deleted transition table stayed green while modeling a state
   flow nothing consumed — so here we assert through dispatch, end to end.

   Two block paths exist and both are exercised:
   - idle-guard: intent type is in `idle-blocked-intents` and state is :background.
   - intent-allowed?: intent's :allowed-states excludes the current state.
   A toggle test proves the guard (not the handler) is what blocks."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [harness.intent-fixtures :as intent-fixtures]
            [kernel.api :as api]
            [kernel.db :as db]
            [kernel.transaction :as tx]))

;; Handlers emit a real, observable op (update-node on "a"), so "did the intent
;; run?" reduces to "did block \"a\"'s text change?".
(defn- mutate-handler [_db _session _intent]
  {:ops [{:op :update-node :id "a" :props {:text "MUTATED"}}]})

(use-fixtures :each
  (intent-fixtures/with-registered-intents
    {;; Universal: allowed in any state, never idle-blocked. Positive control.
     :universal-mutate {:handler mutate-handler}
     ;; Editing-only via :allowed-states — exercises the intent-allowed? branch.
     :edit-only-mutate {:allowed-states #{:editing} :handler mutate-handler}
     ;; In idle-blocked-intents AND permitted in :selection/:editing — exercises
     ;; the idle-guard branch (blocked in :background even though :allowed-states
     ;; would permit it elsewhere).
     :indent {:allowed-states #{:selection :editing} :handler mutate-handler}}))

(def ^:private one-block-db
  "DB with a single block \"a\" under :doc."
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "a" :type :block :props {:text "orig"}}
                      {:op :place :id "a" :under :doc :at :last}])))

(def ^:private background-session
  {:ui {:editing-block-id nil} :selection {:nodes #{} :focus nil :anchor nil}})

(def ^:private selection-session
  {:ui {:editing-block-id nil} :selection {:nodes #{"a"} :focus "a" :anchor "a"}})

(def ^:private editing-session
  {:ui {:editing-block-id "a"} :selection {:nodes #{} :focus nil :anchor nil}})

(defn- block-text [d] (get-in d [:nodes "a" :props :text]))

(deftest dispatch-runs-permitted-intent-test
  (testing "a universal intent mutates the db from every state (positive control)"
    (doseq [session [background-session selection-session editing-session]]
      (let [{:keys [db ops issues]} (api/dispatch one-block-db session {:type :universal-mutate})]
        (is (empty? issues))
        (is (seq ops))
        (is (= "MUTATED" (block-text db)))))))

(deftest dispatch-blocks-state-disallowed-intent-test
  (testing "an :editing-only intent silently no-ops from :selection (intent-allowed? branch)"
    (let [{:keys [db ops issues]} (api/dispatch one-block-db selection-session
                                                {:type :edit-only-mutate})]
      (is (empty? issues) "a state-blocked intent is silent, not an error")
      (is (= [] ops) "no ops emitted")
      (is (= "orig" (block-text db)) "db content unchanged")
      (is (identical? one-block-db db) "db is the same value (true no-op, not a rebuilt copy)")))
  (testing "the same intent runs once we're in :editing"
    (let [{:keys [db ops]} (api/dispatch one-block-db editing-session {:type :edit-only-mutate})]
      (is (seq ops))
      (is (= "MUTATED" (block-text db))))))

(deftest dispatch-idle-guard-blocks-editing-intents-test
  (testing ":indent silently no-ops in :background (idle-guard branch)"
    (let [{:keys [db ops issues]} (api/dispatch one-block-db background-session {:type :indent})]
      (is (empty? issues))
      (is (= [] ops))
      (is (= "orig" (block-text db)))
      (is (identical? one-block-db db))))
  (testing ":indent runs in :selection (allowed there; idle-guard only fires in :background)"
    (let [{:keys [db ops]} (api/dispatch one-block-db selection-session {:type :indent})]
      (is (seq ops))
      (is (= "MUTATED" (block-text db))))))

(deftest dispatch-enforcement-toggle-test
  (testing "with :state-machine/enforce? false, an otherwise-blocked intent runs — proves the guard, not the handler, is what blocks"
    (let [{:keys [db ops]} (api/dispatch one-block-db selection-session
                                         {:type :edit-only-mutate}
                                         {:state-machine/enforce? false})]
      (is (seq ops))
      (is (= "MUTATED" (block-text db))))))
