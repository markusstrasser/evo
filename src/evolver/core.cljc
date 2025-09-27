(ns evolver.core
  (:require [malli.core :as m]))




;; 1. Define the shape of the data
(def ui-tree-schema
  [:map
   [:nodes [:map-of string? [:map [:type keyword?]]]]
   [:children-by-parent [:map-of string? [:vector string?]]]])
;; We will add :references later

;; 2. Create an initial, valid state
(def initial-db
  {:nodes {"root" {:type :root}}
   :children-by-parent {"root" []}})

;; --- Derivation is pure; interpreter calls it between steps ---
(defn derive-update [db] (assoc db :derived {:nothing-yet true}))

(m/validate ui-tree-schema initial-db)
(defn deep-merge [a b] (merge-with #(if (and (map? %1) (map? %2)) (deep-merge %1 %2) %2) a b))

;; 1) Algebra protocol: pure ops over (store, db, payload)
(defprotocol ITreeAlgebra
  (insert [this db {:keys [id under at type props]}])
  (patch  [this db {:keys [id updates]}]))


(defn ->commands [x]
  (letfn [(canon [[op & xs]]
            (case op
              :insert (let [[id m] xs] [:insert (assoc m :id id)])
              :patch  (let [[id m] xs] [:patch  (assoc m :id id)])
              :tx     (let [[ops] xs]  [:tx     {:ops (mapv canon ops)}])
              (throw (ex-info "bad cmd" {:op op :xs xs}))))]
    (cond (nil? x) [] (and (vector? x) (keyword? (first x))) [(canon x)]
          (sequential? x) (mapv canon x) :else (throw (ex-info "bad tx" {:x x})))))

(defn interpret [store db tx]
  (reduce (fn [d [op arg]]
            (let [d (if (:derived d) d (derive-update d))]
              (derive-update (case op
                        :insert (insert store d arg)
                        :patch  (patch  store d arg)
                        :tx     (interpret store d (:ops arg))
                        (throw (ex-info "unknown op" {:op op}))))))
          (derive-update db) (->commands tx)))


