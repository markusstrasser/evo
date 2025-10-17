(ns core.tree
  "Tree traversal utilities for the kernel.")

(defn descendants-of
  "Return all descendant node IDs of the given parent (recursive).
   
   Includes the parent itself if it exists in :children-by-parent.
   Returns empty vector if parent has no children."
  [db parent]
  (let [children-by-parent (:children-by-parent db)]
    (letfn [(collect [node-id]
              (let [children (get children-by-parent node-id [])]
                (if (seq children)
                  (concat children (mapcat collect children))
                  [])))]
      (vec (collect parent)))))
