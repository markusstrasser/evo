(ns kernel.sugar-ops
  "Sugar operations built from the 4 core primitives.
   Non-core helpers: ins, mv, del, reorder, move-up, move-down."
  (:require [kernel.core :as K]))

(defn ins
  "Create + attach. Throws if :id already exists."
  [db {:keys [id parent-id type props pos] :or {type :div props {}}}]
  (assert id "ins: :id required")
  (when (get-in db [:nodes id])
    (throw (ex-info "ins: id already exists" {:id id})))
  (-> db
      (K/ensure-node* {:id id :type type :props props})
      (K/set-parent* {:id id :parent-id parent-id :pos (or pos :last)})))

(defn mv
  "Move-and-place via set-parent* (optionally validate :from-parent-id)."
  [db {:keys [id from-parent-id to-parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "mv: node does not exist: " id))
  (when (and from-parent-id (not (some #{id} (K/child-ids-of* db from-parent-id))))
    (throw (ex-info "mv: :from-parent-id does not contain id" {:id id :from-parent-id from-parent-id})))
  (K/set-parent* db {:id id :parent-id to-parent-id :pos (or pos :last)}))

(defn del
  "Delete id (and its subtree) via purge*."
  [db {:keys [id]}]
  (K/purge* db (fn [_ x] (= x id))))

(defn reorder
  "Within-parent reorder via set-parent*. Uses :parent-id-of from Tier-A."
  [db {:keys [id parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "reorder: node does not exist: " id))
  (let [cur-parent-id (get-in db [:derived :parent-id-of id])]
    (when (and cur-parent-id (not= parent-id cur-parent-id))
      (throw (ex-info "reorder: wrong parent-id" {:id id :given parent-id :actual cur-parent-id})))
    (when (nil? pos)
      (throw (ex-info "reorder: target pos required" {:id id :parent-id parent-id})))
    (K/set-parent* db {:id id :parent-id parent-id :pos pos})))

(defn move-up
  "Move before doc-prev. Requires Tier-B DX pack."
  [db {:keys [id]}]
  (if-let [anchor (get-in db [:derived :doc-prev-id-of id])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/set-parent* db {:id id :parent-id p :pos [:before anchor]}))
    db))

(defn move-down
  "Move after doc-next. Requires Tier-B DX pack."
  [db {:keys [id]}]
  (if-let [anchor (get-in db [:derived :doc-next-id-of id])]
    (let [p (get-in db [:derived :parent-id-of anchor])]
      (K/set-parent* db {:id id :parent-id p :pos [:after anchor]}))
    db))

