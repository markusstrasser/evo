(ns kernel.tree-index
  (:require [kernel.core :as K]))

(defn build [db]
  (let [{:keys [parent-id-of child-ids-of index-of pre post id-by-pre]}
        (or (some-> db :derived (select-keys
              [:parent-id-of :child-ids-of :index-of :pre :post :id-by-pre]))
            (K/derive-core db))]
    {:parent-of   #(get parent-id-of %)
     :children-of #(get child-ids-of % [])
     :index-of    #(get index-of %)
     :pre         #(get pre %)
     :post        #(get post %)
     :id-by-pre   #(get id-by-pre %)}))