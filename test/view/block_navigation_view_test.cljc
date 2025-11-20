(ns view.block-navigation-view-test
  "View-level coverage for navigation scenarios (Logseq parity triad)."
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.constants :as const]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [components.block :as block]
            [view.util :as vu]))

(defn- nav-boundary-db
  "Minimal DB for NAV-BOUNDARY-LEFT-01.
   Parent A with child B; editing B so `.content-edit` span should exist."
  []
  (let [ops [{:op :create-node :id "a" :type :block :props {:text "Parent"}}
             {:op :place :id "a" :under :doc :at :last}
             {:op :create-node :id "b" :type :block :props {:text "Child"}}
             {:op :place :id "b" :under "a" :at :last}]
        base (db/empty-db)
        db* (:db (tx/interpret base ops))]
    (assoc-in db* [:nodes const/session-ui-id :props :editing-block-id] "b")))

(defn- render-block [db block-id]
  (block/Block {:db db
                :block-id block-id
                :depth 0
                :on-intent (constantly nil)}))

(deftest ^{:fr/ids #{:fr.nav/horizontal-boundary}}
  scenario-nav-boundary-left-01-view
  ;; Scenario ID matches docs/specs/logseq_behaviors.md
  (let [db (nav-boundary-db)
        hiccup (render-block db "b")
        edit-el (vu/find-element hiccup :.content-edit)]
    (testing "Editing span is present for NAV-BOUNDARY-LEFT-01"
      (is (some? edit-el) "Missing .content-edit span prevents boundary navigation"))

    (testing "Editing span keeps block-id wiring for Nexus dispatch"
      (is (= "b" (vu/select-attribute hiccup :.content-edit :data-block-id))))

    (testing "Lifecycle hook present so cursor management can run"
      (is (true? (vu/lifecycle-hook? edit-el :replicant/on-render))
          "Removing :replicant/on-render regresses cursor behavior"))

    (testing "Keydown handler exists to trigger :navigate-to-adjacent"
      (is (true? (vu/has-event-handler? edit-el :keydown))
          "Arrow-left boundary handler missing from .content-edit span"))))
