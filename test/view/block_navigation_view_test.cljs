(ns view.block-navigation-view-test
  "View-level coverage for navigation scenarios (Logseq parity triad)."
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [components.block :as block]
            [view.util :as vu]))

(defn- nav-boundary-db
  "Minimal DB for NAV-BOUNDARY-LEFT-01.
   Parent A with child B."
  []
  (let [ops [{:op :create-node :id "a" :type :block :props {:text "Parent"}}
             {:op :place :id "a" :under :doc :at :last}
             {:op :create-node :id "b" :type :block :props {:text "Child"}}
             {:op :place :id "b" :under "a" :at :last}]
        base (db/empty-db)]
    (:db (tx/interpret base ops))))

(defn- render-block
  "Render a block with optional editing state.
   Pass is-editing: true to render in edit mode."
  ([db block-id] (render-block db block-id false))
  ([db block-id is-editing?]
   (block/Block {:db db
                 :block-id block-id
                 :depth 0
                 :is-editing is-editing?
                 :on-intent (constantly nil)})))

(deftest 
  scenario-nav-boundary-left-01-view
  ;; Scenario ID matches docs/specs/logseq_behaviors.md
  (let [db (nav-boundary-db)
        hiccup (render-block db "b" true) ; is-editing = true
        ;; Component uses .block-content for both edit and view modes
        edit-el (vu/find-element hiccup :.block-content)]
    (testing "Editing span is present for NAV-BOUNDARY-LEFT-01"
      (is (some? edit-el) "Missing .block-content span prevents boundary navigation"))

    ;; Note: data-block-id is now only on parent div.block (not .block-content)
    ;; to avoid Playwright strict mode violations. Parent block-id is accessible
    ;; via DOM traversal: element.closest('[data-block-id]')
    (testing "Parent block has block-id wiring for Nexus dispatch"
      (is (= "b" (vu/select-attribute hiccup :div.block :data-block-id))))

    (testing "Lifecycle hook present so cursor management can run"
      (is (true? (vu/lifecycle-hook? edit-el :replicant/on-render))
          "Removing :replicant/on-render regresses cursor behavior"))

    (testing "Keydown handler exists to trigger :navigate-to-adjacent"
      (is (true? (vu/has-event-handler? edit-el :keydown))
          "Arrow-left boundary handler missing from .block-content span"))))
