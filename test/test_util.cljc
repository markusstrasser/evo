(ns test-util
  "Test utilities and macros for transaction testing.

   Enhanced with matcher-combinators for rich diff output."
  (:require [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [clojure.data :as data]
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])
            #?(:clj [matcher-combinators.test :refer [match?]]
               :cljs [matcher-combinators.test :refer [match?]])
            #?(:clj [fipp.edn :refer [pprint]]
               :cljs [cljs.pprint :refer [pprint]])))

;; ── deftx Macro ───────────────────────────────────────────────────────────────

#?(:clj
   (defmacro deftx
     "Define a transaction test that runs, validates, and asserts invariants.

      Usage:
        (deftx move-no-cycle
          {:op :create-node :id \"a\" :type :p :props {}}
          {:op :place :id \"a\" :under :doc :at :first})

      Expands to a deftest that:
        - Interprets the ops against empty-db
        - Asserts no issues
        - Validates final DB invariants"
     [test-name & ops]
     `(clojure.test/deftest ~test-name
        (let [tx# (vec ~(vec ops))
              {:keys [~'db ~'issues]} (tx/interpret (db/empty-db) tx#)]
          (is (empty? ~'issues)
              (str "Transaction should have no issues, got: " (pr-str ~'issues)))
          (is (:ok? (tx/validate ~'db))
              (str "Database invariants should hold after transaction"))))))

;; ── Enhanced Test Assertions ──────────────────────────────────────────────────

(defn assert-intent-ops
  "Assert intent produces expected ops. Prints full context on failure.

   Example:
     (assert-intent-ops db
       {:type :merge-with-next :block-id \"a\"}
       [{:op :update-node :id \"a\" :props {:text \"merged\"}}])"
  [db intent expected-ops]
  (let [{:keys [ops]} (intent/apply-intent db intent)]
    (when-not (match? expected-ops ops)
      (println "\n=== INTENT ASSERTION FAILED ===")
      (println "Intent:" (pr-str intent))
      (println "\nExpected ops:")
      (pprint expected-ops)
      (println "\nActual ops:")
      (pprint ops)
      (println "\nDiff:")
      (pprint (data/diff expected-ops ops)))
    (is (match? expected-ops ops))))

(defn assert-db-nodes
  "Assert DB nodes match expected shape. Supports partial matching.

   Example:
     (assert-db-nodes db
       {\"a\" {:text \"Hello\"}
        \"b\" {:parent :trash}})"
  [db node-id->expected-props]
  (doseq [[node-id expected-props] node-id->expected-props]
    (let [actual-props (get-in db [:nodes node-id :props])]
      (when-not (match? expected-props actual-props)
        (println "\n=== NODE ASSERTION FAILED ===")
        (println "Node ID:" node-id)
        (println "\nExpected props:")
        (pprint expected-props)
        (println "\nActual props:")
        (pprint actual-props))
      (is (match? expected-props actual-props)
          (str "Node " node-id " props mismatch")))))

(defn assert-tree-structure
  "Assert tree structure matches expected parent-child relationships.

   Example:
     (assert-tree-structure db
       {:doc [\"a\" \"b\"]
        \"a\" [\"c\" \"d\"]})"
  [db expected-tree]
  (doseq [[parent-id expected-children] expected-tree]
    (let [actual-children (get-in db [:children-by-parent parent-id])]
      (when-not (= expected-children actual-children)
        (println "\n=== TREE STRUCTURE MISMATCH ===")
        (println "Parent:" parent-id)
        (println "Expected children:" expected-children)
        (println "Actual children:" actual-children))
      (is (= expected-children actual-children)
          (str "Children of " parent-id " don't match")))))

(defn apply-intent-and-interpret
  "Apply intent and return resulting db (convenience helper).

   Example:
     (apply-intent-and-interpret db {:type :select :ids [\"a\"]})"
  [db intent]
  (let [{:keys [ops]} (intent/apply-intent db intent)]
    (:db (tx/interpret db ops))))

(defn intent-chain
  "Apply multiple intents in sequence, returning final db.

   Example:
     (intent-chain db
       {:type :enter-edit :block-id \"a\"}
       {:type :update-content :block-id \"a\" :text \"new\"}
       {:type :exit-edit})"
  [db & intents]
  (reduce apply-intent-and-interpret db intents))
