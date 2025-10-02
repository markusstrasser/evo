(ns viz
 "Tree visualization utilities for REPL exploration."
 (:require [clojure.string :as str]))

(defn show-tree
 "Pretty-print tree structure with Unicode box-drawing characters.

  Args:
    db    - Database with :nodes and :children-by-parent
    root  - Optional root ID or keyword (defaults to first root in :roots)

  Example:
    (show-tree db)
    (show-tree db :doc)
    (show-tree db \"root-node-id\")

  Output:
    :doc
    ├─ node-1 [:div]
    │  ├─ child-a [:span]
    │  └─ child-b [:p]
    └─ node-2 [:div]"
 ([db]
  (let [root (first (:roots db))]
   (show-tree db root)))
 ([db root]
  (let [{:keys [nodes children-by-parent]} db

        render-node (fn render-node [id depth last? parent-indents]
                     (let [node (get nodes id)
                           prefix (cond
                                   (zero? depth) ""
                                   last? "└─ "
                                   :else "├─ ")
                           indent (str/join parent-indents)
                           label (if node
                                  (str id " [" (:type node) "]")
                                  (str id))
                           line (str indent prefix label)
                           children (get children-by-parent id [])
                           child-indent (if last? "   " "│  ")
                           new-indents (conj parent-indents child-indent)
                           child-lines (map-indexed
                                        (fn [idx child-id]
                                         (render-node child-id
                                                      (inc depth)
                                                      (= idx (dec (count children)))
                                                      new-indents))
                                        children)]
                      (str/join "\n" (cons line child-lines))))]

   (println (render-node root 0 false []))
   nil)))

(comment
 ;; Test with fixtures
 (require '[fixtures :as f])
 (require '[core.db :as db])
 (require '[core.interpret :as i])

 ;; Simple tree
 (show-tree (:db f/simple-tree))
 ;; => root [:div]
 ;;    ├─ a [:span]
 ;;    └─ b [:p]

 ;; Balanced tree
 (let [{:keys [db root-id]} (f/gen-balanced-tree 3 2)]
  (show-tree db root-id))

 ;; Linear tree
 (let [{:keys [db ids]} (f/gen-linear-tree 5)]
  (show-tree db (first ids)))

 ;; With kernel operations
 (let [result (i/interpret (db/empty-db)
                           [{:op :create-node :id "a" :type :div :props {}}
                            {:op :create-node :id "b" :type :span :props {}}
                            {:op :create-node :id "c" :type :p :props {}}
                            {:op :place :id "a" :under :doc :at 0}
                            {:op :place :id "b" :under "a" :at 0}
                            {:op :place :id "c" :under "a" :at 1}])]
  (show-tree (:db result) :doc))
 ;; => :doc
 ;;    └─ a [:div]
 ;;       ├─ b [:span]
 ;;       └─ c [:p]
 )
