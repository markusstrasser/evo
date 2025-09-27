(ns evolver.tx-plus-protocol-at-edges
  (:require [clojure.pprint :refer [pprint]]))

;; ---------- pure core over canonical db ----------
(defn rmv [v x] (vec (remove #{x} v)))
(defn clamp [i lo hi] (-> i (max lo) (min hi)))

(defn derive-parent-of [children-by-parent]
  (reduce-kv (fn [m p kids] (reduce #(assoc %1 %2 p) m kids))
             {} children-by-parent))
(defn ensure-derived [db]
  (assoc db :parent-of (derive-parent-of (:children-by-parent db))))

(defn ins* [db {:keys [id under type props at] :or {type :div props {} at :last}}]
  (-> db
      (assoc-in [:nodes id] {:type type :props props})
      (update-in [:children-by-parent under] (fnil identity []))
      (update-in [:children-by-parent under]
                 (fn [kids]
                   (case at
                     :first (vec (cons id (rmv kids id)))
                     :last  (conj (rmv kids id) id)
                     (let [base (rmv kids id)
                           i (if (and (vector? at) (= :index (first at)))
                               (clamp (second at) 0 (count base))
                               (count base))]
                       (vec (concat (subvec base 0 i) [id] (subvec base i)))))))
      ensure-derived))

(defn patch*   [db {:keys [id updates]}]
  (-> db
      (update-in [:nodes id]
                 #(merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                              % updates))
      ensure-derived))

(defn mv*      [db {:keys [id from to]}]
  (if (= from to) db
                  (-> db
                      (update-in [:children-by-parent from] (fnil rmv []) id)
                      (update-in [:children-by-parent to]   (fnil conj []) id)
                      ensure-derived)))

(defn reorder* [db {:keys [id parent to]}]
  (let [kids (get-in db [:children-by-parent parent] [])
        base (rmv kids id)
        i    (clamp (or to 0) 0 (count base))]
    (-> db
        (assoc-in [:children-by-parent parent]
                  (vec (concat (subvec base 0 i) [id] (subvec base i))))
        ensure-derived)))

(defn del*     [db {:keys [id]}]
  (let [p (get-in (ensure-derived db) [:parent-of id])]
    (-> db
        (update-in [:children-by-parent p] (fnil rmv []) id)
        (update  :children-by-parent dissoc id)
        (update  :nodes dissoc id)
        ensure-derived)))

(defn interpret* [db tx]
  (reduce (fn [d [op payload]]
            (case op
              :ins     (ins* d payload)
              :patch   (patch* d payload)
              :mv      (mv* d payload)
              :reorder (reorder* d payload)
              :del     (del* d payload)))
          (ensure-derived db) tx))

;; ---------- optional ports (thin) ----------
(defprotocol ITreeView
  (parent-of   [this db id])
  (children-of [this db id]))

(defprotocol ITreeEdit
  (ins     [this db payload])
  (patch   [this db payload])
  (mv      [this db payload])       ;; requires :from in payload
  (reorder [this db payload])
  (del     [this db payload]))

(defrecord CanonicalAdapter []
  ITreeView
  (parent-of   [_ db id] (get-in (ensure-derived db) [:parent-of id]))
  (children-of [_ db id] (get-in db [:children-by-parent id] []))
  ITreeEdit
  (ins     [_ db p] (ins* db p))
  (patch   [_ db p] (patch* db p))
  (mv      [_ db p] (mv* db p))
  (reorder [_ db p] (reorder* db p))
  (del     [_ db p] (del* db p)))

;; usage:
;; (def A (->CanonicalAdapter))
;; (-> db (ins A {:id "x" :under "root"}) (reorder A {:id "x" :parent "root" :to 0}))