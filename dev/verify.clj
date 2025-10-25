(ns verify
  (:require [kernel.db :as DB]
            [kernel.api :as api]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [kernel.query :as q]))

(defn assert= [a b msg]
  (when-not (= a b)
    (throw (ex-info msg {:expected b :got a}))))

(defn ids [db parent]
  (q/children db parent))

(comment
  ;; Pre-flight sanity check
  (-> (DB/empty-db)
      (tx/interpret [{:op :create-node :id "x" :type :block :props {:text "hi"}}
                     {:op :place :id "x" :under :doc :at :last}])
      :db
      :nodes
      (get "x"))
  ;; => {:type :block, :props {:text "hi"}}

  ;; End-to-end integration test
  (let [{:keys [db]} (tx/interpret (DB/empty-db)
                                   [{:op :create-node :id "page" :type :page :props {:title "P"}}
                                    {:op :place :id "page" :under :doc :at :last}
                                    {:op :create-node :id "a" :type :block :props {:text "A"}}
                                    {:op :place :id "a" :under "page" :at :last}
                                    {:op :create-node :id "b" :type :block :props {:text "B"}}
                                    {:op :place :id "b" :under "page" :at :last}])]
    ;; UI ephemeral: enter/exit edit does not affect undo
    (def db1 (:db (api/dispatch db {:type :enter-edit :block-id "a"})))
    (assert= (q/editing-block-id db1) "a" "enter-edit failed")
    (assert= (H/undo-count db1) 0 "UI-only op should not record history")

    ;; Structural change records history
    (def db2 (:db (api/dispatch db1 {:type :indent :id "b"})))
    (assert= (H/undo-count db2) 1 "structural op must record history")

    ;; Undo returns to prior structure, preserves ephemeral (editing block stays)
    (def db3 (or (H/undo db2) db2))
    (assert= (q/editing-block-id db3) (q/editing-block-id db2) "ephemeral should persist across undo")
    (assert= (ids db3 "page") ["a" "b"] "undo of indent should restore siblings")

    ;; Selection is undoable (stored in session/selection)
    (def db4 (:db (api/dispatch db3 {:type :select :ids ["a" "b"]})))
    (assert= (H/undo-count db4) 2 "selection change should record history")
    (assert= (q/selection-count db4) 2 "selection size")
    (println :OK))
  )
