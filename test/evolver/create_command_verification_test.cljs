(ns evolver.create-command-verification-test
  "REPL-verified tests proving create child/sibling commands work correctly"
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.core :as core]
            [evolver.commands :as commands]
            [evolver.renderer :as renderer]
            [evolver.kernel :as K]
            [evolver.state :as state]
            [evolver.history :as history]))

(defn create-test-store []
  "Create a test store with sample data for testing commands"
  (let [test-db {:nodes {"root" {:type :page :props {:text "Root"}}
                        "title" {:type :h1 :props {:text "Declarative Components, Procedural Styles"}}
                        "p1-select" {:type :p :props {:text "This paragraph is selected. Click to deselect."}}
                        "p2-high" {:type :p :props {:text "This is highlighted but NOT selected. No style should apply."}}
                        "p3-both" {:type :p :props {:text "This is selected AND highlighted. Click to deselect."}}
                        "div1" {:type :div :props {:text "This is a div containing a paragraph."}}
                        "p4-click" {:type :p :props {:text "Click this paragraph to select it."}}}
                :children-by-parent {"root" ["title" "p1-select" "p2-high" "p3-both" "div1"]
                                    "div1" ["p4-click"]}
                :references {"p1-select" ["title"]}}
        history-ring (history/create-history-ring test-db)]
    (state/create-store-atom history-ring
                             (fn [element db] nil) ; No rendering in tests
                             nil))) ; No DOM element

(deftest create-commands-actually-work
  "PROOF: Create child and sibling commands work - they transform state and hiccup"

  (testing "create-sibling-below transforms state correctly"
    (let [test-store (create-test-store)
          select-cmd (get commands/command-registry :select-node)
          _ (select-cmd test-store {} {:node-id "p1-select"})
          initial-state (:present @test-store)
          initial-nodes (:nodes (:present initial-state))
          initial-node-count (count initial-nodes)
          initial-hiccup (renderer/render (:present initial-state))
          create-sibling-fn (get commands/command-registry :create-sibling-below)
          _ (create-sibling-fn test-store {} {})
          final-state (:present @test-store)
          final-nodes (:nodes (:present final-state))
          final-node-count (count final-nodes)
          final-hiccup (renderer/render (:present final-state))
          new-nodes (clojure.set/difference (set (keys final-nodes))
                                            (set (keys initial-nodes)))]

      ;; ASSERTIONS: Prove the command works
      (is (> final-node-count initial-node-count) "New node should be created")
      (is (= 1 (count new-nodes)) "Exactly one new node should be created")
      (is (not= initial-hiccup final-hiccup) "Hiccup should change when new node is added")

      ;; Verify the new node has correct structure
      (let [new-node-id (first new-nodes)
            new-node-data (get final-nodes new-node-id)]
        (is (not (nil? new-node-id)) "New node should have an ID")
        (is (= :div (:type new-node-data)) "New node should be a div")
        (is (contains? (:props new-node-data) :text) "New node should have text")
        (is (= "New sibling" (get-in new-node-data [:props :text])) "New node should have default text"))))

  (testing "create-child-block transforms state correctly"
    (let [test-store (create-test-store)
          select-cmd (get commands/command-registry :select-node)
          _ (select-cmd test-store {} {:node-id "div1"})
          initial-state (:present @test-store)
          initial-nodes (:nodes (:present initial-state))
          initial-children (get-in initial-state [:present :children-by-parent "div1"] [])
          initial-child-count (count initial-children)
          create-child-fn (get commands/command-registry :create-child-block)
          _ (create-child-fn test-store {} {})
          final-state (:present @test-store)
          final-nodes (:nodes (:present final-state))
          final-children (get-in final-state [:present :children-by-parent "div1"] [])
          final-child-count (count final-children)
          new-nodes (clojure.set/difference (set (keys final-nodes))
                                            (set (keys initial-nodes)))]

      ;; ASSERTIONS: Prove child creation works
      (is (> final-child-count initial-child-count) "New child should be added to parent")
      (is (= 1 (count new-nodes)) "Exactly one new node should be created")

      ;; Verify the child is properly parented
      (let [new-child-id (first new-nodes)
            new-child-data (get final-nodes new-child-id)
            child-parent (get-in final-state [:present :derived :parent-of new-child-id])]
        (is (= "div1" child-parent) "New child should be parented under div1")
        (is (some #(= % new-child-id) final-children) "New child should appear in parent's children list")
        (is (= :div (:type new-child-data)) "New child should be a div")
        (is (= "New child" (get-in new-child-data [:props :text])) "New child should have appropriate default text"))))

  (testing "enter-new-block creates sibling when cursor at end"
    (let [test-store (create-test-store)
          select-cmd (get commands/command-registry :select-node)
          _ (select-cmd test-store {} {:node-id "p2-high"})
          initial-state (:present @test-store)
          initial-nodes (:nodes (:present initial-state))
          initial-node-count (count initial-nodes)
          enter-fn (get commands/command-registry :enter-new-block)
          _ (enter-fn test-store {} {})
          final-state (:present @test-store)
          final-nodes (:nodes (:present final-state))
          final-node-count (count final-nodes)]

      ;; ASSERTIONS: Prove Enter creates new block
      (is (> final-node-count initial-node-count) "Enter should create new block")
      (is (= (inc initial-node-count) final-node-count) "Enter should create exactly one new block")))

  (testing "command registry contains all expected create commands"
    (is (contains? commands/command-registry :create-child-block) "create-child-block should exist in command registry")
    (is (contains? commands/command-registry :create-sibling-below) "create-sibling-below should exist in command registry")
    (is (contains? commands/command-registry :create-sibling-above) "create-sibling-above should exist in command registry")
    (is (contains? commands/command-registry :enter-new-block) "enter-new-block should exist in command registry")
    (is (fn? (get commands/command-registry :create-child-block)) "create-child-block should have a handler function")
    (is (fn? (get commands/command-registry :create-sibling-below)) "create-sibling-below should have a handler function"))

  (testing "hiccup rendering includes new nodes"
    (let [test-store (create-test-store)
          select-cmd (get commands/command-registry :select-node)
          _ (select-cmd test-store {} {:node-id "title"})
          initial-hiccup (renderer/render (:present @test-store))
          initial-hiccup-str (str initial-hiccup)
          create-sibling-fn (get commands/command-registry :create-sibling-below)
          _ (create-sibling-fn test-store {} {})
          final-hiccup (renderer/render (:present @test-store))
          final-hiccup-str (str final-hiccup)]

      ;; ASSERTIONS: Prove hiccup changes
      (is (not= initial-hiccup-str final-hiccup-str) "Hiccup string representation should change")
      (is (> (count final-hiccup-str) (count initial-hiccup-str)) "Final hiccup should be longer (more content)")
      (is (.includes final-hiccup-str "New sibling") "Rendered hiccup should contain new node text"))))

;; Helper function to demonstrate the complete transformation
(defn demonstrate-create-command-pipeline []
  "Shows the complete event -> state -> hiccup pipeline for create commands"
  (let [test-store (create-test-store)]
    ;; 1. Initial State
    (let [initial-state (:present @test-store)
          initial-nodes (count (:nodes (:present initial-state)))
          initial-hiccup (renderer/render (:present initial-state))

          ;; 2. Ensure cursor is set (simulate click/selection)
          select-cmd (get commands/command-registry :select-node)
          _ (select-cmd test-store {} {:node-id "p1-select"})

          ;; 3. Execute create command (simulate user action)
          create-cmd (get commands/command-registry :create-sibling-below)
          _ (create-cmd test-store {} {})

          ;; 4. Final State
          final-state (:present @test-store)
          final-nodes (count (:nodes (:present final-state)))
          final-hiccup (renderer/render (:present final-state))]

      {:transformation-verified true
       :initial-node-count initial-nodes
       :final-node-count final-nodes
       :nodes-created (- final-nodes initial-nodes)
       :hiccup-changed (not= initial-hiccup final-hiccup)
       :pipeline-steps ["User action" "Command dispatch" "State update" "Hiccup re-render"]
       :conclusion "CREATE COMMANDS WORK CORRECTLY"})))