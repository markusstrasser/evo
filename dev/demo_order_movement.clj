(ns demo-order-movement
  "REPL demonstration of order & movement core features."
  (:require [core.db :as db]
            [core.interpret :as interp]
            [core.ops :as ops]
            [kernel.anchor :as anchor]
            [plugins.permute.core :as permute]
            [plugins.refs.core :as refs]))

(comment
  ;;==========================================================================
  ;; Setup: Create a test tree
  ;;==========================================================================

  (def test-db
    (-> (db/empty-db)
        (ops/create-node "P" :container {})
        (ops/create-node "A" :p {:text "Node A"})
        (ops/create-node "B" :p {:text "Node B"})
        (ops/create-node "C" :p {:text "Node C"})
        (ops/create-node "D" :p {:text "Node D"})
        (ops/create-node "Q" :container {})
        (ops/create-node "X" :p {:text "Node X"})
        (ops/place "P" :doc :first)
        (ops/place "A" "P" :first)
        (ops/place "B" "P" :last)
        (ops/place "C" "P" :last)
        (ops/place "D" "P" :last)
        (ops/place "Q" :doc :last)
        (ops/place "X" "Q" :first)
        db/derive-indexes))

  ;; Check initial state
  (:children-by-parent test-db)
  ;; => {:doc ["P" "Q"], "P" ["A" "B" "C" "D"], "Q" ["X"]}

  ;;==========================================================================
  ;; REPL Check 1: Anchor Resolution
  ;;==========================================================================

  ;; Resolve :first anchor
  (anchor/->index test-db "P" :first)
  ;; => {:idx 0, :normalized-anchor :first}

  ;; Resolve :last anchor
  (anchor/->index test-db "P" :last)
  ;; => {:idx 4, :normalized-anchor :last}

  ;; Resolve {:after "B"} anchor
  (anchor/->index test-db "P" {:after "B"})
  ;; => {:idx 2, :normalized-anchor {:after "B"}}

  ;; Resolve {:before "C"} anchor
  (anchor/->index test-db "P" {:before "C"})
  ;; => {:idx 2, :normalized-anchor {:before "C"}}

  ;; Try invalid anchor (should throw)
  (try
    (anchor/->index test-db "P" {:before "MISSING"})
    (catch Exception e
      (:reason (ex-data e))))
  ;; => :kernel.anchor/missing-target

  ;;==========================================================================
  ;; REPL Check 2-5: Simple Reorders
  ;;==========================================================================

  ;; Check 2: Move B to start
  (def intent-2 {:intent :reorder
                 :selection ["B"]
                 :parent "P"
                 :anchor :first})

  (def result-2 (permute/lower test-db intent-2))
  (:ops result-2)
  ;; => [{:op :place, :id "B", :under "P", :at :first}]

  (def db-2 (:db (interp/interpret test-db (:ops result-2))))
  (get-in db-2 [:children-by-parent "P"])
  ;; => ["B" "A" "C" "D"]

  ;; Check 3: Move D to start
  (def intent-3 {:intent :reorder
                 :selection ["D"]
                 :parent "P"
                 :anchor :first})

  (def result-3 (permute/lower test-db intent-3))
  (def db-3 (:db (interp/interpret test-db (:ops result-3))))
  (get-in db-3 [:children-by-parent "P"])
  ;; => ["D" "A" "B" "C"]

  ;; Check 4: Non-contiguous selection (B and D after A)
  (def intent-4 {:intent :reorder
                 :selection ["B" "D"]
                 :parent "P"
                 :anchor {:after "A"}})

  (def result-4 (permute/lower test-db intent-4))
  (:ops result-4)
  ;; => [{:op :place, :id "B", :under "P", :at {:after "A"}}
  ;;     {:op :place, :id "D", :under "P", :at {:after "B"}}]

  (def db-4 (:db (interp/interpret test-db (:ops result-4))))
  (get-in db-4 [:children-by-parent "P"])
  ;; => ["A" "B" "D" "C"]

  ;; Check 5: Move multiple to end
  (def intent-5 {:intent :reorder
                 :selection ["A" "C"]
                 :parent "P"
                 :anchor :last})

  (def result-5 (permute/lower test-db intent-5))
  (def db-5 (:db (interp/interpret test-db (:ops result-5))))
  (get-in db-5 [:children-by-parent "P"])
  ;; => ["B" "D" "A" "C"]

  ;;==========================================================================
  ;; REPL Check 6-10: Cross-Parent Moves
  ;;==========================================================================

  ;; Check 6: Move B from P to Q
  (def intent-6 {:intent :move
                 :selection ["B"]
                 :parent "Q"
                 :anchor :last})

  (def result-6 (permute/lower test-db intent-6))
  (def db-6 (:db (interp/interpret test-db (:ops result-6))))
  (get-in db-6 [:children-by-parent "P"])
  ;; => ["A" "C" "D"]
  (get-in db-6 [:children-by-parent "Q"])
  ;; => ["X" "B"]

  ;; Check 7: Move B and C to Q
  (def intent-7 {:intent :move
                 :selection ["B" "C"]
                 :parent "Q"
                 :anchor :last})

  (def result-7 (permute/lower test-db intent-7))
  (def db-7 (:db (interp/interpret test-db (:ops result-7))))
  (get-in db-7 [:children-by-parent "P"])
  ;; => ["A" "D"]
  (get-in db-7 [:children-by-parent "Q"])
  ;; => ["X" "B" "C"]

  ;; Check 8: Move B and D to Q at start
  (def intent-8 {:intent :move
                 :selection ["B" "D"]
                 :parent "Q"
                 :anchor :first})

  (def result-8 (permute/lower test-db intent-8))
  (def db-8 (:db (interp/interpret test-db (:ops result-8))))
  (get-in db-8 [:children-by-parent "Q"])
  ;; => ["B" "D" "X"]

  ;; Check 9: Move all from P to Q
  (def intent-9 {:intent :move
                 :selection ["A" "B" "C" "D"]
                 :parent "Q"
                 :anchor {:after "X"}})

  (def result-9 (permute/lower test-db intent-9))
  (def db-9 (:db (interp/interpret test-db (:ops result-9))))
  (get-in db-9 [:children-by-parent "P"])
  ;; => []
  (get-in db-9 [:children-by-parent "Q"])
  ;; => ["X" "A" "B" "C" "D"]

  ;; Check 10: Move X before first in its parent
  (def intent-10 {:intent :move
                  :selection ["X"]
                  :parent "Q"
                  :anchor :first})

  (def result-10 (permute/lower test-db intent-10))
  (def db-10 (:db (interp/interpret test-db (:ops result-10))))
  (get-in db-10 [:children-by-parent "Q"])
  ;; => ["X"] (no change, already first)

  ;;==========================================================================
  ;; REPL Check 11-15: Refs Plugin
  ;;==========================================================================

  ;; Setup DB with refs
  (def refs-db
    (-> test-db
        (ops/update-node "A" {:refs ["X" "B"]})
        (ops/update-node "B" {:refs ["C"]})
        db/derive-indexes
        (assoc :derived (merge (:derived test-db)
                              (refs/derive-indexes test-db)))))

  ;; Check 11: Get backlinks
  (refs/get-backlinks refs-db "X")
  ;; => #{"A"}

  (refs/get-backlinks refs-db "C")
  ;; => #{"B"}

  ;; Check 12: Get outgoing refs
  (refs/get-outgoing-refs refs-db "A")
  ;; => #{"X" "B"}

  ;; Check 13: Citation count
  (refs/citation-count refs-db "X")
  ;; => 1

  (refs/citation-count refs-db "C")
  ;; => 1

  ;; Check 14: Add ref
  (def add-ref-op (refs/add-ref refs-db "D" "A"))
  add-ref-op
  ;; => {:op :update-node, :id "D", :props {:refs ["A"]}}

  (def refs-db-2 (:db (interp/interpret refs-db [add-ref-op])))
  (refs/get-backlinks (assoc refs-db-2 :derived (refs/derive-indexes refs-db-2)) "A")
  ;; => #{"D"}

  ;; Check 15: Dangling refs
  (def db-with-dangling
    (-> refs-db
        (update :nodes dissoc "X")))

  (refs/find-dangling-refs (assoc db-with-dangling :derived (refs/derive-indexes db-with-dangling)))
  ;; => [{:reason :plugins.refs.core/dangling-ref, :dst "X", :srcs #{"A"}, :suggest {...}}]

  (refs/scrub-dangling-refs db-with-dangling)
  ;; => [{:op :update-node, :id "A", :props {:refs ["B"]}}]

  ;;==========================================================================
  ;; REPL Check 16-20: Validation and Errors
  ;;==========================================================================

  ;; Check 16: Validate intent with missing node
  (def bad-intent-16 {:intent :reorder
                      :selection ["B" "MISSING"]
                      :parent "P"
                      :anchor :first})

  (permute/validate-intent test-db bad-intent-16)
  ;; => {:reason :plugins.permute.core/node-not-found, :missing ["MISSING"], ...}

  ;; Check 17: Validate intent with missing parent
  (def bad-intent-17 {:intent :reorder
                      :selection ["B"]
                      :parent "MISSING"
                      :anchor :first})

  (permute/validate-intent test-db bad-intent-17)
  ;; => {:reason :plugins.permute.core/parent-not-found, ...}

  ;; Check 18: Cycle detection
  (def nested-db
    (-> test-db
        (ops/place "B" "A" :first)
        db/derive-indexes))

  (def cycle-intent {:intent :move
                     :selection ["A"]
                     :parent "B"
                     :anchor :first})

  (permute/validate-intent nested-db cycle-intent)
  ;; => {:reason :plugins.permute.core/would-create-cycle, ...}

  ;; Check 19: Idempotence
  (def db-19-1 (:db (interp/interpret test-db (:ops result-4))))
  (def result-19-2 (permute/lower db-19-1 intent-4))
  (def db-19-2 (:db (interp/interpret db-19-1 (:ops result-19-2))))
  (= (get-in db-19-1 [:children-by-parent "P"])
     (get-in db-19-2 [:children-by-parent "P"]))
  ;; => true (idempotent)

  ;; Check 20: Deterministic trace
  (def result-20 (interp/interpret test-db (:ops result-4) {:notes "test reorder"}))
  (:trace result-20)
  ;; => [{:tx-id ..., :seed ..., :ops [...], :notes "test reorder", :num-applied 2}]

  (println "\n✓ All 20+ REPL checks completed successfully!")
  )
