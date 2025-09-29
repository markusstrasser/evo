(ns kernel.effects)

(defn detect
  "Pure detector: look at prev & next db and the op applied.
   Return a vector of effect maps; adapters run them."
  [prev next op-index op]
  (cond-> []
    (= (:op op) :insert)
    (conj {:effect :view/scroll-into-view
           :ids [(:id op)]
           :cause {:op-index op-index :op (:op op)}})))