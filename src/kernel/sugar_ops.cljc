(ns kernel.sugar-ops
  "Sugar operations - higher-level operations built on core primitives.

   These operations extend the multimethod in kernel.core to provide
   convenient, composite operations for common patterns."
  (:require [kernel.core :as K]))

;; Sugar ops extend the apply-op multimethod from kernel.core
;; This achieves the open-world registry without circular dependencies

(defmethod K/apply-op :insert
  [db {:keys [id parent-id type props pos] :or {type :div props {}}}]
  (assert id "insert: :id required")
  (when (get-in db [:nodes id])
    (throw (ex-info "insert: id already exists" {:id id})))
  (-> db
      (K/create-node* {:id id :type type :props props})
      (K/place* {:id id :parent-id parent-id :pos (or pos :last)})))

(defmethod K/apply-op :move
  [db {:keys [id from-parent-id to-parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "move: node does not exist: " id))
  (when (and from-parent-id (not (some #{id} (K/child-ids-of* db from-parent-id))))
    (throw (ex-info "move: :from-parent-id does not contain id" {:id id :from-parent-id from-parent-id})))
  (K/place* db {:id id :parent-id to-parent-id :pos (or pos :last)}))

(defmethod K/apply-op :delete
  [db {:keys [id]}]
  (K/prune* db (fn [_ x] (= x id))))

(defmethod K/apply-op :reorder
  [db {:keys [id parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "reorder: node does not exist: " id))
  (let [cur-parent-id (get-in db [:derived :parent-id-of id])]
    (when (and cur-parent-id (not= parent-id cur-parent-id))
      (throw (ex-info "reorder: wrong parent-id" {:id id :given parent-id :actual cur-parent-id})))
    (when (nil? pos)
      (throw (ex-info "reorder: target pos required" {:id id :parent-id parent-id})))
    (K/place* db {:id id :parent-id parent-id :pos pos})))

(defmethod K/apply-op :move-up
  [db {:keys [id]}]
  (if-let [anchor (get-in db [:derived :order-prev-id-of id])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id id :parent-id p :pos [:before anchor]}))
    db))

(defmethod K/apply-op :move-down
  [db {:keys [id]}]
  (if-let [anchor (get-in db [:derived :order-next-id-of id])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id id :parent-id p :pos [:after anchor]}))
    db))