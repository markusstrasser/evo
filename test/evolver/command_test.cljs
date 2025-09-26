(ns evolver.command-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.commands :as commands]
            [evolver.kernel :as kernel]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [jtbd user-story acceptance-criteria test-with-agent-validation]]))

(defn create-test-store []
  (helpers/create-test-store kernel/db))

(deftest test-command-registry
  (testing "Command registry contains expected commands"
    (is (contains? commands/command-registry :select-node))
    (is (contains? commands/command-registry :clear-selection))
    (is (contains? commands/command-registry :create-child-block))
    (is (contains? commands/command-registry :undo))
    (is (contains? commands/command-registry :redo)))

  (testing "All command handlers are functions"
    (doseq [[cmd-name handler] commands/command-registry]
      (is (fn? handler) (str "Handler for " cmd-name " should be a function")))))

(deftest test-command-dispatch
  (testing "Command dispatch works correctly"
    (let [store (create-test-store)]
      ;; Just test that dispatch doesn't throw and store gets updated properly
      (commands/dispatch-command store {} [:select-node {:node-id "p2-high"}])
      (is (= #{"p2-high"} (get-in @store [:view :selection-set])))))

  (testing "Invalid command handling"
    (let [store (create-test-store)
          original-store @store]
      ;; Should log error but not crash
      (commands/dispatch-command store {} [:invalid-command {}])
      ;; Check that error was logged
      (is (> (count (:log-history @store)) (count (:log-history original-store)))
          "Error should be logged"))))

(deftest test-selection-commands
  (testing "Select node command updates selection"
    (let [store (create-test-store)]
      (is (= #{"p1-select"} (get-in @store [:view :selection-set])))

      (commands/dispatch-command store {} [:select-node {:node-id "p2-high"}])
      (is (= #{"p2-high"} (get-in @store [:view :selection-set])))))

  (testing "Clear selection command works"
    (let [store (create-test-store)]
      (do (swap! store assoc-in [:view :selection] ["p1-select" "p2-high"])
          (swap! store assoc-in [:view :selection-set] #{"p1-select" "p2-high"}))
      (is (= #{"p1-select" "p2-high"} (get-in @store [:view :selection-set])))

      (commands/dispatch-command store {} [:clear-selection {}])
      (is (= #{} (get-in @store [:view :selection-set]))))))

(deftest test-keyboard-command-integration
  (testing "Keyboard commands are registered"
    (is (contains? commands/command-registry :navigate-sibling))
    (is (contains? commands/command-registry :delete-selected-blocks))
    (is (contains? commands/command-registry :indent-block)))

  (testing "Keyboard commands work with store"
    (let [store (create-test-store)]
      ;; Test navigation
      (do (swap! store assoc-in [:view :selection] ["p1-select"])
          (swap! store assoc-in [:view :selection-set] #{"p1-select"}))
      (commands/dispatch-command store {} [:navigate-sibling {:direction :down}])
      (is (= #{"p2-high"} (get-in @store [:view :selection-set]))))))

;; Duplicate test removed - keeping only the self-documenting version

;; Keeping only the self-documenting version in command-system-feature

;; Self-documenting feature test example
(deftest command-system-feature
  "Command system enables structured document manipulation"

  (jtbd "Execute structured operations on document content"
        (user-story "Dispatch individual commands to modify document state"
                    (acceptance-criteria "Select node command updates selection correctly"
                                         (test-with-agent-validation
                                          (let [store (create-test-store)]
                                            (commands/dispatch-command store {} [:select-node {:node-id "p2-high"}])
                                            (is (= #{"p2-high"} (get-in @store [:view :selection-set]))))))

                    (acceptance-criteria "Clear selection removes all selected nodes"
                                         (test-with-agent-validation
                                          (let [store (create-test-store)]
                                            (do (swap! store assoc-in [:view :selection] ["p1-select" "p2-high"])
                                                (swap! store assoc-in [:view :selection-set] #{"p1-select" "p2-high"}))
                                            (commands/dispatch-command store {} [:clear-selection {}])
                                            (is (= #{} (get-in @store [:view :selection-set])))))))

        (user-story "Handle multiple commands in sequence"
                    (acceptance-criteria "Batch command execution maintains state consistency"
                                         (test-with-agent-validation
                                          (let [store (create-test-store)]
                                            (commands/dispatch-commands store {}
                                                                        [[:select-node {:node-id "p2-high"}]
                                                                         [:clear-selection {}]])
                                            (is (= #{} (get-in @store [:view :selection-set])))))))))