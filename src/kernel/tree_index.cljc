(ns kernel.tree-index
  (:require [kernel.derive.registry :as registry]))

(defn build [db]
  (let [derived-db (if (:derived db) db (registry/run db))
        {:keys [parent-id-of child-ids-of index-of pre post id-by-pre]}
        (select-keys (:derived derived-db)
                     [:parent-id-of :child-ids-of :index-of :pre :post :id-by-pre])]
    {:parent-of   #(get parent-id-of %)
     :children-of #(get child-ids-of % [])
     :index-of    #(get index-of %)
     :pre         #(get pre %)
     :post        #(get post %)
     :id-by-pre   #(get id-by-pre %)}))