(ns core.demo-struct
  "Demo and smoke test for structural editing intent compiler.
   Shows compilation of intents → ops → final tree state."
  (:require [core.db :as D]
            [core.interpret :as I]
            [core.struct :as S]))

(defn demo-struct
  "One-call demo showing structural editing intents in action.

   Creates a simple document structure:
     doc1 -> [a, b]

   Then applies three intents:
     1. Indent b under a        → doc1 -> [a -> [b]]
     2. Outdent b back to doc1  → doc1 -> [a, b]
     3. Delete a                → doc1 -> [b], trash -> [a]

   Returns map with:
     :ops    - compiled operation vector
     :issues - any validation issues (should be empty)
     :tree   - final children of doc1
     :trash  - nodes in trash"
  []
  (let [;; Build initial structure
        DB0 (D/empty-db)
        {:keys [db]} (I/interpret DB0
                       [{:op :create-node :id "doc1" :type :doc :props {}}
                        {:op :place :id "doc1" :under :doc :at :last}
                        {:op :create-node :id "a" :type :p :props {}}
                        {:op :place :id "a" :under "doc1" :at :last}
                        {:op :create-node :id "b" :type :p :props {}}
                        {:op :place :id "b" :under "doc1" :at :last}])

        ;; Compile three structural intents
        intents [{:type :indent :id "b"}
                 {:type :outdent :id "b"}
                 {:type :delete :id "a"}]

        ops (S/compile-intents db intents)

        ;; Execute compiled ops
        {:keys [db issues]} (I/interpret db ops)

        ;; Extract final state
        tree  (get-in db [:children-by-parent "doc1"])
        trash (get-in db [:children-by-parent :trash])]

    {:ops    ops
     :issues issues
     :tree   tree
     :trash  trash}))

(comment
  ;; REPL usage:
  (demo-struct)
  ;; => {:ops    [{:op :place, :id "b", :under "a", :at :last}
  ;;              {:op :place, :id "b", :under "doc1", :at {:after "a"}}
  ;;              {:op :place, :id "a", :under :trash, :at :last}]
  ;;     :issues []
  ;;     :tree   ["b"]
  ;;     :trash  ["a"]}

  ;; Verify structural editing laws:
  (let [{:keys [tree trash issues]} (demo-struct)]
    (and (= ["b"] tree)
         (= ["a"] trash)
         (empty? issues)))
  ;; => true
  )