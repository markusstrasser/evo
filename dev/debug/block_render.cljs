(ns dev.debug.block-render
  "Dev tools for debugging block rendering issues"
  (:require [kernel.api :as api]
            [kernel.queries :as q]))

;; REPL helpers for inspecting block rendering state

(defn inspect-block
  "Inspect a block's rendering state in detail"
  [db block-id]
  (let [node (get-in db [:nodes block-id])
        editing-id (q/editing-block-id db)
        cursor-pos (q/cursor-position db)]
    {:block-id block-id
     :text (get-in node [:props :text])
     :type (:type node)
     :editing? (= block-id editing-id)
     :cursor-position cursor-pos
     :parent (get-in db [:derived :parent-of block-id])
     :children (get-in db [:children-by-parent block-id])}))

(defn inspect-dom-block
  "Inspect what's actually in the DOM for a block"
  [block-id]
  (when-let [node (js/document.querySelector (str "[data-block-id='" block-id "']"))]
    {:block-id block-id
     :dom-text (.-textContent node)
     :contenteditable? (.hasAttribute node "contenteditable")
     :is-focused? (= node js/document.activeElement)
     :dataset (js->clj (.-dataset node))}))

(defn compare-db-vs-dom
  "Compare DB state vs actual DOM state for a block"
  [db block-id]
  {:db (inspect-block db block-id)
   :dom (inspect-dom-block block-id)})

(defn find-contenteditable
  "Find all contenteditable elements in the DOM"
  []
  (let [nodes (js/document.querySelectorAll "[contenteditable='true']")]
    (map (fn [node]
           {:block-id (.-blockId (.-dataset node))
            :text (.-textContent node)
            :is-focused? (= node js/document.activeElement)})
         (array-seq nodes))))

(defn snapshot-render-cycle
  "Take a snapshot of the render cycle for debugging"
  [db]
  {:editing-block-id (q/editing-block-id db)
   :cursor-position (q/cursor-position db)
   :contenteditable-elements (find-contenteditable)
   :all-blocks (map #(inspect-block db %)
                    (keys (get-in db [:nodes])))})

;; Dev tools - attach to window for browser console access
(defn ^:export init-debug-tools [!db]
  (set! (.-DEBUG js/window)
        #js {:inspectBlock #(clj->js (inspect-block @!db %))
             :inspectDOM #(clj->js (inspect-dom-block %))
             :compareDBvsDOM #(clj->js (compare-db-vs-dom @!db %))
             :findContenteditable #(clj->js (find-contenteditable))
             :snapshot #(clj->js (snapshot-render-cycle @!db))
             :state (fn [] @!db)}))
