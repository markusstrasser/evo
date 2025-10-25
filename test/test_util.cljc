(ns test-util
  "Test utilities and macros for transaction testing."
  (:require [core.db :as db]
            [core.transaction :as tx]
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])))

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
