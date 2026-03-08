(ns utils.block-dom
  "DOM helpers shared by shell-level and block-level adapters.")

(defn get-adjacent-block-by-dom
  "Find adjacent block ID by DOM order.

   This is used as a visual-order fallback in journals view, where blocks from
   different pages render together and DB-local traversal may hit a page edge
   before the DOM does."
  [direction current-block-id]
  (when-let [current-el (.querySelector js/document
                                        (str "[data-block-id='" current-block-id "']"))]
    (let [all-blocks (array-seq (.querySelectorAll js/document
                                                   ".block[data-block-id]"))
          current-idx (.indexOf all-blocks current-el)
          target-idx (case direction
                       :up (dec current-idx)
                       :down (inc current-idx))]
      (when (and (>= target-idx 0) (< target-idx (count all-blocks)))
        (-> (nth all-blocks target-idx)
            (.getAttribute "data-block-id"))))))
