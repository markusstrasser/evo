(ns labs.workspace)

(defn empty-workspace [] {:collapsed #{}})

(defn toggle-collapsed [ws id]
  (update ws :collapsed #(if (% id) (disj % id) (conj % id))))

(defn collapsed? [ws id]
  ((:collapsed ws) id))