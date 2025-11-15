(ns dev.debug.enter-key-trace
  "REPL script to trace Enter key rendering bug"
  (:require [kernel.api :as api]
            [kernel.queries :as q]
            [kernel.constants :as const]))

(comment
  ;; REPL workflow to debug Enter key duplication

  ;; 1. Get current DB state
  (def db @shell.blocks-ui/!db)

  ;; 2. Find the Tech Stack block
  (def tech-stack-id "proj-2")

  ;; 3. Check current state
  (get-in db [:nodes tech-stack-id :props :text])
  ;=> "Tech Stack: ClojureScript + Replicant"

  (q/editing-block-id db)
  ;=> "proj-2" (if editing)

  (q/cursor-position db)
  ;=> 37 (cursor at end)

  ;; 4. Simulate Enter key press at position 37
  (def intent {:type :context-aware-enter
               :block-id tech-stack-id
               :cursor-pos 37})

  (def result (api/dispatch db intent))

  ;; 5. Inspect the result
  (:operations result) ;=> What operations were generated?

  (def new-db (:db result))

  ;; 6. Find the new block that was created
  (def children-after (get-in new-db [:children-by-parent :projects]))
  ;=> Should show new block between tech-stack and "See also"

  (def new-block-id (second (drop-while #(not= % tech-stack-id) children-after)))

  ;; 7. Inspect the new block
  (get-in new-db [:nodes new-block-id])
  ;=> Should show {:type :block :props {:text ""}}

  (get-in new-db [:nodes new-block-id :props :text])
  ;=> Should be ""

  ;; 8. Check editing state
  (q/editing-block-id new-db)
  ;=> Should be new-block-id

  (q/cursor-position new-db)
  ;=> Should be 0 (cursor at start of new empty block)

  ;; 9. Manually check DOM
  (js/console.log "Contenteditable elements:"
                  (-> (js/document.querySelectorAll "[contenteditable='true']")
                      array-seq
                      (->> (map (fn [el]
                                  {:block-id (.-blockId (.-dataset el))
                                   :text (.-textContent el)
                                   :focused? (= el js/document.activeElement)})))))

  ;; 10. Compare DB state vs DOM state
  (defn compare-state [db]
    (let [editing-id (q/editing-block-id db)
          expected-text (get-in db [:nodes editing-id :props :text])
          dom-el (js/document.querySelector (str "[data-block-id='" editing-id "']"))
          actual-text (when dom-el (.-textContent dom-el))]
      {:editing-block-id editing-id
       :db-says-text expected-text
       :dom-shows-text actual-text
       :match? (= expected-text actual-text)}))

  (compare-state new-db)
  ;=> {:editing-block-id "block-123..."
  ;    :db-says-text ""
  ;    :dom-shows-text "See also: [[Tasks]] page for work items"  ← BUG!
  ;    :match? false}

  )

;; Automated trace function to run from browser console
(defn ^:export trace-enter-press
  "Trace an Enter key press step by step. Call from console: DEBUG.traceEnter('proj-2', 37)"
  [block-id cursor-pos]
  (let [db @shell.blocks-ui/!db
        before-text (get-in db [:nodes block-id :props :text])
        intent {:type :context-aware-enter
                :block-id block-id
                :cursor-pos cursor-pos}
        result (api/dispatch db intent)
        new-db (:db result)
        children-after (get-in new-db [:children-by-parent :projects])
        new-block-id (second (drop-while #(not= % block-id) children-after))
        new-text (get-in new-db [:nodes new-block-id :props :text])]

    (js/console.log "=== ENTER KEY TRACE ===")
    (js/console.log "Before:" (clj->js {:block-id block-id
                                        :text before-text
                                        :cursor-pos cursor-pos}))
    (js/console.log "Operations:" (clj->js (:operations result)))
    (js/console.log "New block created:" (clj->js {:block-id new-block-id
                                                   :text new-text
                                                   :should-be-empty? (empty? new-text)}))
    (js/console.log "Editing state:" (clj->js {:editing-id (q/editing-block-id new-db)
                                               :cursor-pos (q/cursor-position new-db)}))

    ;; Wait for DOM update, then check
    (js/setTimeout
     (fn []
       (let [dom-el (js/document.querySelector (str "[data-block-id='" new-block-id "']"))
             dom-text (when dom-el (.-textContent dom-el))]
         (js/console.log "DOM state:" (clj->js {:new-block-id new-block-id
                                                :dom-text dom-text
                                                :matches-db? (= new-text dom-text)}))
         (when-not (= new-text dom-text)
           (js/console.error "❌ BUG: DOM text doesn't match DB!"
                             (clj->js {:expected new-text
                                       :actual dom-text})))))
     100)

    result))
