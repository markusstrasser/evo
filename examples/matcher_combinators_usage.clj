(ns examples.matcher-combinators-usage
  "Examples showing how matcher-combinators makes testing MUCH easier.

  Problem: Writing exact assertions for deeply nested data is brittle.
  Solution: Match on *shape* and *important values* only.

  This is perfect for event-sourced systems where you care about structure
  but not every single derived value."
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]
            [matcher-combinators.matchers :as m]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 1: Test DB shape without brittle exact matches
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-create-block-operation
  (testing "Creating a block produces correct DB shape"
    ;; ❌ OLD WAY: Brittle - fails if any derived field changes
    #_(is (= {:nodes {"block-1" {:type :block
                                  :props {:text "Hello"}}}
              :children-by-parent {:doc ["block-1"]}
              :roots [:doc :trash :session]
              :derived {:parent-of {"block-1" :doc}
                        :next-id-of {}
                        :prev-id-of {}
                        :index-of {"block-1" 0}
                        :pre {"block-1" 0}
                        :post {"block-1" 0}
                        :id-by-pre {0 "block-1"}}}
             result-db))
    ;; ^ This breaks if you add ANY new derived field!

    ;; ✅ NEW WAY: Match only what matters
    (is (match? {:nodes {"block-1" {:type :block
                                    :props {:text "Hello"}}}
                 :children-by-parent {:doc ["block-1"]}
                 :derived {:parent-of {"block-1" :doc}}}
                result-db))
    ;; ^ Passes even if derived fields get new keys!
    ))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 2: Use matchers for flexible assertions
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-with-matchers
  (let [result {:nodes {"a" {:type :block :props {:text "Hello"}}
                        "b" {:type :block :props {:text "World"}}}
                :children-by-parent {:doc ["a" "b"]}
                :derived {:parent-of {"a" :doc "b" :doc}
                          :index-of {"a" 0 "b" 1}}}]

    ;; Match collections of any size
    (is (match? {:nodes map?}  ; Just check it's a map
                result))

    ;; Match values with predicates
    (is (match? {:children-by-parent {:doc (m/embeds ["a"])}}  ; Contains "a"
                result))

    ;; Ignore specific keys
    (is (match? {:nodes m/any
                 :children-by-parent m/any
                 :derived (m/match-with [map? (partial every? map?)]
                                        {:parent-of map?})}
                result))

    ;; Check nested structure
    (is (match? {:nodes {"a" {:type :block
                              :props {:text string?}}}}  ; text is any string
                result))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 3: Test operations produce expected shape
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-move-operation-shape
  (testing "Move operations have correct structure"
    (let [ops [{:op :update :id "block-a" :props {:text "New text"}}
               {:op :place :id "block-a" :under :doc :at :first}]]

      ;; ✅ Check operation shape without caring about exact values
      (is (match? [{:op :update
                    :id string?
                    :props map?}
                   {:op :place
                    :id string?
                    :under keyword?
                    :at keyword?}]
                  ops))

      ;; Check specific operation has right keys
      (is (match? (m/embeds [{:op :place
                              :under :doc}])
                  ops)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 4: Great error messages when tests fail
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-error-messages
  (testing "Matcher-combinators gives helpful diffs"
    (let [actual {:nodes {"a" {:type :block :props {:text "Wrong"}}}
                  :extra-key "unexpected"}]

      ;; When this fails, you get a VISUAL DIFF showing:
      ;; - What was expected
      ;; - What was actual
      ;; - Exactly where they differ

      (is (match? {:nodes {"a" {:type :block
                                :props {:text "Expected"}}}}
                  actual))
      ;; Output shows:
      ;; {:nodes {"a" {:type :block
      ;;                :props {:text (expected "Expected"
      ;;                                actual "Wrong")}}}}
      )))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 5: Perfect for event sourcing - test state transitions
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-event-sourcing-workflow
  (testing "Applying operations produces correct state transition"
    (let [initial-db {:nodes {}
                      :children-by-parent {:doc []}}

          operations [{:op :create :id "a" :type :block :props {:text "Hello"}}
                      {:op :place :id "a" :under :doc :at :last}]

          ;; Apply ops (using your kernel.api/transact!)
          result-db (apply-operations initial-db operations)]

      ;; ✅ Test the important parts - don't care about derived indexes
      (is (match? {:nodes {"a" {:type :block
                                :props {:text "Hello"}}}
                   :children-by-parent {:doc ["a"]}}
                  result-db))

      ;; Test derived indexes exist (but don't care about exact values)
      (is (match? {:derived {:parent-of map?
                             :index-of map?}}
                  result-db)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 6: Advanced matchers for complex scenarios
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-advanced-matchers
  (let [db {:nodes {"a" {:type :block :props {:text "Hello"}}
                    "b" {:type :block :props {:text "World"}}
                    "c" {:type :page :props {:title "Page"}}}
            :children-by-parent {:doc ["a" "b"]
                                 "page-1" ["c"]}}]

    ;; Match maps with specific keys
    (is (match? {:nodes (m/match-with [map?]
                                      {"a" map?
                                       "b" map?})}
                db))

    ;; Check collection contains items in any order
    (is (match? {:children-by-parent {:doc (m/in-any-order ["b" "a"])}}
                db))

    ;; Partial matching - other keys ok
    (is (match? (m/embeds {:nodes map?})
                db))

    ;; Predicate matching
    (is (match? {:nodes (m/pred #(>= (count %) 2))}  ; At least 2 nodes
                db))

    ;; Regex matching
    (is (match? {:nodes {"a" {:props {:text (m/regex #"Hel.*")}}}}
                db))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 7: Real Evo test - navigation operations
;; ═══════════════════════════════════════════════════════════════════════════

(deftest test-navigate-up-operation
  (testing "Navigate up from block B selects block A"
    (let [db {:nodes {"a" {:type :block :props {:text "First"}}
                      "b" {:type :block :props {:text "Second"}}}
              :children-by-parent {:doc ["a" "b"]}
              :session {:editing-block-id "b" :cursor-pos 0}}

          ;; User presses up arrow
          result (apply-intent db {:intent :navigation/up
                                   :context {:current-block-id "b"}})]

      ;; ✅ Don't care about exact DB structure, just that:
      ;; 1. We're editing block "a" now
      ;; 2. Cursor is at end of text
      (is (match? {:session {:editing-block-id "a"
                             :cursor-pos 5}}  ; end of "First"
                  result))

      ;; Don't care if other session fields changed
      ;; Don't care about derived indexes
      ;; Don't care about operation history
      )))

;; ═══════════════════════════════════════════════════════════════════════════
;; Why this is MUCH better for Evo
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; ❌ WITHOUT matcher-combinators:
  ;; - Tests break when you add new derived fields
  ;; - Tests break when you add new session keys
  ;; - Need to specify EVERY field even if irrelevant
  ;; - Hard to see what actually matters in the test
  ;; - Cryptic "expected X, got Y" messages

  ;; ✅ WITH matcher-combinators:
  ;; - Test only what matters (the important state)
  ;; - Add new fields without breaking tests
  ;; - Clear intent: "editing-block-id should be 'a'"
  ;; - Beautiful diffs when tests fail
  ;; - Flexible predicates (string?, map?, #(> % 0))

  ;; PERFECT FOR:
  ;; - Event sourcing (state transitions)
  ;; - Derived data (don't care about exact indexes)
  ;; - Integration tests (complex state)
  ;; - Property-based tests (check shapes, not values)
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; Quick reference - Common matchers
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; Predicates
  (match? {:count pos-int?} {:count 5})
  (match? {:text string?} {:text "hello"})

  ;; Embeds (partial matching)
  (match? (m/embeds {:a 1}) {:a 1 :b 2})  ; :b is ignored

  ;; In any order
  (match? (m/in-any-order [1 2 3]) [3 1 2])

  ;; Regex
  (match? (m/regex #"hell.*") "hello")

  ;; Prefix (for collections)
  (match? (m/prefix [1 2]) [1 2 3 4])

  ;; Custom predicates
  (match? (m/pred #(> % 10)) 15)

  ;; Equals (exact match - default behavior)
  (match? (m/equals {:a 1}) {:a 1})

  ;; Any (matches anything)
  (match? m/any "literally anything")
  )
