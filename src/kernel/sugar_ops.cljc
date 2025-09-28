(ns kernel.sugar-ops
  (:require [kernel.core :as K]
            [kernel.opkit :refer [defop]]
            [kernel.schemas :as S]))

;; alias registry names for brevity in schema forms
(def Kid :kernel.schemas/id)
(def Kpos :kernel.schemas/pos)

(defop :insert
  {:doc "Create node then place it under parent."
   :schema [:map
            [:op [:= :insert]]
            [:id Kid]
            [:parent-id Kid]
            [:type {:optional true} keyword?]
            [:props {:optional true} map?]
            [:pos {:optional true} Kpos]]}
  (let [{:keys [id parent-id type props pos]} op
        type (or type :div) props (or props {}) pos (or pos :last)]
    (-> db
        (K/create-node* {:id id :type type :props props})
        (K/place* {:id id :parent-id parent-id :pos pos}))))

(defop :move
  {:doc "Move (and optionally reorder) a node to a new parent."
   :schema [:map
            [:op [:= :move]]
            [:id Kid]
            [:to-parent-id Kid]
            [:from-parent-id {:optional true} Kid]
            [:pos {:optional true} Kpos]]}
  (let [{:keys [id from-parent-id to-parent-id pos]} op]
    (when (and from-parent-id
               (not (some #{id} (K/child-ids-of* db from-parent-id))))
      (throw (ex-info "move: :from-parent-id does not contain id"
                      {:id id :from-parent-id from-parent-id})))
    (K/place* db {:id id :parent-id to-parent-id :pos (or pos :last)})))

(defop :delete
  {:doc "Delete node and its subtree."
   :schema [:map [:op [:= :delete]] [:id Kid]]}
  (K/prune* db (fn [_ x] (= x (:id op)))))

(defop :reorder
  {:doc "Reposition within current parent using :pos."
   :schema [:map [:op [:= :reorder]] [:id Kid] [:parent-id Kid] [:pos Kpos]]}
  (let [{:keys [id parent-id pos]} op
        cur (get-in db [:derived :parent-id-of id])]
    (when (and cur (not= parent-id cur))
      (throw (ex-info "reorder: wrong parent-id" {:id id :given parent-id :actual cur})))
    (when (nil? pos) (throw (ex-info "reorder: target pos required" {:id id :parent-id parent-id})))
    (K/place* db {:id id :parent-id parent-id :pos pos})))

(defop :move-up
  {:doc "Move node before previous in document order." :schema [:map [:op [:= :move-up]] [:id Kid]]}
  (if-let [anchor (get-in db [:derived :order-prev-id-of (:id op)])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id (:id op) :parent-id p :pos [:before anchor]}))
    db))

(defop :move-down
  {:doc "Move node after next in document order." :schema [:map [:op [:= :move-down]] [:id Kid]]}
  (if-let [anchor (get-in db [:derived :order-next-id-of (:id op)])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id (:id op) :parent-id p :pos [:after anchor]}))
    db))