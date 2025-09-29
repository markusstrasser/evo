(ns kernel.sugar-ops
  (:require [kernel.core :as K]
            [kernel.lens :as lens]
            [kernel.opkit :refer [defop]]
            [kernel.schemas :as S]))

;; Simple schema aliases to avoid circular dependencies
(def Knode-id string?)
(def Kanchor any?)

(defop :insert
  {:doc "Create node then place it under parent."
   :schema [:map
            [:op [:= :insert]]
            [:node-id Knode-id]
            [:parent-id Knode-id]
            [:node-type {:optional true} keyword?]
            [:props {:optional true} map?]
            [:anchor {:optional true} Kanchor]]}
  (let [{:keys [node-id parent-id node-type props anchor]} op
        node-type (or node-type :div) props (or props {}) anchor (or anchor :last)]
    (-> db
        (K/create-node* {:id node-id :type node-type :props props})
        (K/place* {:id node-id :parent-id parent-id :pos anchor}))))

(defop :move
  {:doc "Move (and optionally reorder) a node to a new parent."
   :schema [:map
            [:op [:= :move]]
            [:node-id Knode-id]
            [:target-parent-id Knode-id]
            [:from-parent-id {:optional true} Knode-id]
            [:anchor {:optional true} Kanchor]]}
  (let [{:keys [node-id from-parent-id target-parent-id anchor]} op]
    (when (and from-parent-id
               (not (some #{node-id} (lens/children-of db from-parent-id))))
      (throw (ex-info "move: :from-parent-id does not contain id"
                      {:id node-id :from-parent-id from-parent-id})))
    (K/place* db {:id node-id :parent-id target-parent-id :pos (or anchor :last)})))

(defop :delete
  {:doc "Delete node and its subtree."
   :schema [:map [:op [:= :delete]] [:node-id Knode-id]]}
  (K/prune* db (fn [_ x] (= x (:node-id op)))))

(defop :reorder
  {:doc "Reposition within current parent using :anchor."
   :schema [:map [:op [:= :reorder]] [:node-id Knode-id] [:parent-id Knode-id] [:anchor Kanchor]]}
  (let [{:keys [node-id parent-id anchor]} op
        cur (get-in db [:derived :parent-id-of node-id])]
    (when (and cur (not= parent-id cur))
      (throw (ex-info "reorder: wrong parent-id" {:id node-id :given parent-id :actual cur})))
    (when (nil? anchor) (throw (ex-info "reorder: target anchor required" {:id node-id :parent-id parent-id})))
    (K/place* db {:id node-id :parent-id parent-id :pos anchor})))

(defop :move-up
  {:doc "Move node before previous in document order." :schema [:map [:op [:= :move-up]] [:node-id Knode-id]]}
  (if-let [anchor (get-in db [:derived :document-prev-id-of (:node-id op)])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id (:node-id op) :parent-id p :pos [:before-id anchor]}))
    db))

(defop :move-down
  {:doc "Move node after next in document order." :schema [:map [:op [:= :move-down]] [:node-id Knode-id]]}
  (if-let [anchor (get-in db [:derived :document-next-id-of (:node-id op)])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/place* db {:id (:node-id op) :parent-id p :pos [:after-id anchor]}))
    db))